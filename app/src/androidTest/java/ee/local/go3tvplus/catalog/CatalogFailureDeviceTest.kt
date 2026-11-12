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

@RunWith(AndroidJUnit4::class)
class CatalogFailureDeviceTest {
    private class Gateway : Go3Gateway by DemoGo3Gateway() {
        var offline = false
        var partial = true
        var tickets = 0
        var detailsGate: CompletableDeferred<Unit>? = null
        val detailsStarted = CompletableDeferred<Unit>()
        private fun raw(profile: String, id: String) = Json.parseToJsonElement("""{"id":"$id","title":"$profile Öö $id","type":"VOD","active":true}""").jsonObject
        override suspend fun catalogRights(accessToken: String, profileId: String): CatalogRights {
            if (offline) throw Go3Failure.Unavailable("Test network unavailable")
            return CatalogRights(mapOf("one" to null, "two" to null))
        }
        override suspend fun catalogPage(accessToken: String, profileId: String, offset: Int, limit: Int): CatalogPage {
            if (offline || offset > 0) throw Go3Failure.Unavailable("Test interrupted page")
            return CatalogPage(listOf(raw(profileId, "one"), raw(profileId, "two")), if (partial) 3 else 2, 2)
        }
        override suspend fun catalogDetail(accessToken: String, profileId: String, id: String): JsonObject {
            detailsStarted.complete(Unit)
            detailsGate?.await()
            if (offline) throw Go3Failure.Unavailable("Test detail unavailable")
            return raw(profileId, id)
        }
        override suspend fun movieTicket(accessToken: String, profileId: String, movieId: String): PlaybackTicket {
            tickets++; error("Metadata must not request video")
        }
    }
    @Test fun interruptedRefreshPreservesCacheAndRightsFailuresAreVisibleAndProfilesCancelRequests() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(instrument.targetContext, AppDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val gateway = Gateway()
        val tokens = object : TokenStore {
            override fun load() = AuthTokens("test-only-not-a-real-token")
            override fun save(tokens: AuthTokens) = Unit
            override fun clear() = Unit
        }
        val repository = CatalogRepository(gateway, AuthCoordinator(gateway, tokens, scope), db, scope)
        try {
            assertTrue(runCatching { repository.refresh("a") }.isFailure)
            assertEquals(2, repository.titles("a").first().size)
            assertEquals(ViewingRight.AVAILABLE, repository.titles("a").first().first().right)
            assertEquals(0, db.catalogDao().syncSnapshot("a")!!.catalogCheckedAt)
            gateway.offline = true
            val model = CatalogViewModel(repository, scope, instrument.targetContext)
            instrument.runOnMainSync { model.setProfile("a"); model.select(CatalogSection.MOVIES) }
            withTimeout(5000) { model.state.first { it.error != null && !it.loading && it.titles.size == 2 } }
            assertTrue(model.state.value.error!!.contains("network"))
            assertEquals(2, model.state.value.titles.size)
            assertNull(model.state.value.titles.first().poster)
            gateway.offline = false; gateway.partial = false
            repository.refresh("b")
            val gate = CompletableDeferred<Unit>(); gateway.detailsGate = gate
            instrument.runOnMainSync { model.open(model.state.value.titles.first()) }
            withTimeout(5000) { gateway.detailsStarted.await() }
            instrument.runOnMainSync { model.setProfile("b") }
            gate.complete(Unit)
            withTimeout(5000) { model.state.first { it.profile == "b" && it.titles.size == 2 } }
            assertNull(model.state.value.detail)
            assertTrue(model.state.value.titles.all { it.title.startsWith("b ") })
            assertTrue(model.state.value.list.isEmpty()); assertTrue(model.state.value.progress.isEmpty())
            assertEquals(0, gateway.tickets)
            // Unknown is distinct from a confirmed locked item when a profile has no rights snapshot.
            db.catalogDao().saveTitles(listOf(CatalogEntity(profileId = "unknown", id = "x", kind = "MOVIE", normalizedText = "unknown", json = """{"id":"x","type":"VOD","title":"Unknown"}""", seriesId = null, seasonId = null, detailed = false, updatedAt = 1)))
            assertEquals(ViewingRight.UNKNOWN, repository.titles("unknown").first().single().right)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }
    }
}
