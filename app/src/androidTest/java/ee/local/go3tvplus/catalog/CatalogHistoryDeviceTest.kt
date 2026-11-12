package ee.local.go3tvplus.catalog

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ee.local.go3tvplus.data.AuthCoordinator
import ee.local.go3tvplus.data.DemoGo3Gateway
import ee.local.go3tvplus.data.local.AppDatabase
import ee.local.go3tvplus.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class CatalogHistoryDeviceTest {
    private class Gateway : Go3Gateway by DemoGo3Gateway() {
        var offline = false
        var emptyHistory = false
        var remoteContinue = true
        var episodeLoads = 0
        var remotePosition = 20_000L
        var remoteUpdated = System.currentTimeMillis() - 20_000
        var afterUpload: (suspend () -> Unit)? = null
        val sent = mutableListOf<Pair<String, Long>>()
        override suspend fun catalogHistory(accessToken: String, profileId: String): List<CatalogHistoryRecord> {
            if (offline) throw Go3Failure.Unavailable("Test offline")
            if (emptyHistory) return emptyList()
            val raw = Json.parseToJsonElement("""{"items":[{"item":{"id":"one","type":"VOD","title":"Film","duration":100,"status":{"bookmark":${remotePosition / 1000.0}}},"modifiedAt":"${Instant.ofEpochMilli(remoteUpdated)}","watchedAt":null}]}""").jsonObject
            return parseCatalogHistory(raw).map { it.copy(continueEligible = remoteContinue) }
        }
        override suspend fun catalogRights(accessToken: String, profileId: String) = CatalogRights(mapOf("one" to null, "e1" to null, "e2" to null, "e3" to null))
        override suspend fun catalogSeasons(accessToken: String, profileId: String, seriesId: String) = listOf(CatalogSeason("season", seriesId, 1, "First"))
        override suspend fun catalogEpisodes(accessToken: String, profileId: String, seriesId: String, seasonId: String): List<JsonObject> {
            episodeLoads++
            return (1..3).map { Json.parseToJsonElement("""{"id":"e$it","type":"EPISODE","title":"Episode $it","episode":$it,"duration":100}""").jsonObject }
        }
        override suspend fun saveCatalogHistory(accessToken: String, profileId: String, contentId: String, positionMs: Long) {
            if (offline) throw Go3Failure.Unavailable("Test offline")
            sent += profileId to positionMs
            remotePosition = positionMs
            afterUpload?.invoke(); afterUpload = null
        }
    }
    @Test fun remoteImportOfflineOutboxRewindsAndConcurrentWritesStayProfileIsolated() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = Gateway()
        val tokens = object : TokenStore {
            override fun load() = AuthTokens("test-only-not-a-real-token")
            override fun save(tokens: AuthTokens) = Unit
            override fun clear() = Unit
        }
        val auth = AuthCoordinator(gateway, tokens, scope)
        var repository = CatalogRepository(gateway, auth, db, scope)
        val dao = db.catalogDao()
        try {
            repository.syncHistory("a", true)
            assertEquals(20_000L, dao.progressItem("a", "one")!!.positionMs)
            assertEquals(ViewingRight.AVAILABLE, repository.titles("a").first().single().right)
            assertTrue(dao.historyQueue("a").first().isEmpty())
            // Go3 can remove Continue membership without changing the bookmark revision.
            gateway.remoteContinue = false
            repository.syncHistory("a", true)
            assertFalse(dao.progressItem("a", "one")!!.continueEligible)
            gateway.remoteContinue = true
            repository.syncHistory("a", true)
            assertTrue(dao.progressItem("a", "one")!!.continueEligible)
            val now = System.currentTimeMillis()
            dao.saveLocalProgress(ProgressEntity("a", "one", null, 60_000, 100_000, now))
            dao.saveLocalProgress(ProgressEntity("b", "one", null, 70_000, 100_000, now))
            gateway.offline = true
            assertTrue(runCatching { repository.syncHistory("a", true) }.isFailure)
            assertEquals(60_000L, dao.historyQueue("a").first().single().positionMs)
            // A fresh repository instance can recover the durable queue.
            repository = CatalogRepository(gateway, auth, db, scope)
            gateway.offline = false
            repository.syncHistory("a", true)
            assertEquals("a" to 60_000L, gateway.sent.last())
            assertTrue(dao.historyQueue("a").first().isEmpty())
            assertEquals(1, dao.historyQueue("b").first().size)
            assertTrue(gateway.sent.none { it.first == "b" })
            // Another device intentionally rewinds; the newer time beats the larger position.
            gateway.remotePosition = 10_000; gateway.remoteUpdated = now + 10_000
            repository.syncHistory("a", true)
            assertEquals(10_000L, dao.progressItem("a", "one")!!.positionMs)
            // A new local seek arriving during upload must not be acknowledged with the old one.
            dao.saveLocalProgress(ProgressEntity("a", "one", null, 50_000, 100_000, now + 20_000))
            gateway.afterUpload = { dao.saveLocalProgress(ProgressEntity("a", "one", null, 80_000, 100_000, now + 30_000)) }
            repository.syncHistory("a")
            assertEquals(80_000L, dao.historyQueue("a").first().single().positionMs)
            repository.syncHistory("a")
            assertEquals("a" to 80_000L, gateway.sent.last())
            assertTrue(dao.historyQueue("a").first().isEmpty())
            gateway.emptyHistory = true
            repository.syncHistory("a", true)
            assertNull(dao.progressItem("a", "one"))
            assertEquals(1, dao.historyQueue("b").first().size)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }
    }
    @Test fun partialAccountHistoryCannotBeMistakenForACompleteSeason() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = Gateway()
        val tokens = object : TokenStore {
            override fun load() = AuthTokens("test-only-not-a-real-token")
            override fun save(tokens: AuthTokens) = Unit
            override fun clear() = Unit
        }
        val repository = CatalogRepository(gateway, AuthCoordinator(gateway, tokens, scope), db, scope)
        try {
            repository.seasons("a", "series")
            db.catalogDao().saveTitles(listOf(CatalogEntity(profileId = "a", id = "e2", kind = "EPISODE", normalizedText = "episode two",
                json = """{"id":"e2","type":"EPISODE","title":"Episode 2","episode":2,"duration":100}""", seriesId = "series", seasonId = "season", detailed = false, updatedAt = 1)))
            assertEquals(listOf("e1", "e2", "e3"), repository.completeEpisodes("a", "series", "season").sortedBy { it.episode }.map { it.id })
            assertEquals(1, gateway.episodeLoads)
            assertEquals(3, repository.completeEpisodes("a", "series", "season").size)
            assertEquals(1, gateway.episodeLoads)
            repository.seasons("a", "series")
            repository.completeEpisodes("a", "series", "season")
            assertEquals(2, gateway.episodeLoads)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }
    }

}
