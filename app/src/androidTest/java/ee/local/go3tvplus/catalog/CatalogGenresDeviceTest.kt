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
class CatalogGenresDeviceTest {
    private class Gateway : Go3Gateway by DemoGo3Gateway() {
        var interrupted = true
        var rankingOffline = false
        var details = 0
        var tickets = 0
        private fun movie(profile: String, id: String) = Json.parseToJsonElement("""{"id":"$id","type":"VOD","title":"$profile $id","originalTitle":"Original $id","lead":"Keep description","year":2024}""").jsonObject
        override suspend fun catalogRights(accessToken: String, profileId: String) = CatalogRights(mapOf("one" to null, "two" to null))
        override suspend fun catalogPage(accessToken: String, profileId: String, offset: Int, limit: Int) =
            CatalogPage(listOf(movie(profileId, "one"), movie(profileId, "two")), 2, 2)
        override suspend fun catalogGenrePage(accessToken: String, profileId: String, offset: Int, limit: Int): CatalogPage {
            if (offset > 0 && interrupted) throw Go3Failure.Unavailable("Interrupted genre page")
            val id = if (offset == 0) "one" else "two"
            val genre = if (offset == 0) "Märul" else "Draama"
            return CatalogPage(listOf(Json.parseToJsonElement("""{"id":"$id","genres":[{"name":"$genre"}]}""").jsonObject), 2, 1)
        }
        override suspend fun catalogPopularMovies(accessToken: String, profileId: String): List<JsonObject> {
            if (rankingOffline) throw Go3Failure.Unavailable("Ranking offline")
            return listOf(movie(profileId, "two"), movie(profileId, "one"))
        }
        override suspend fun catalogDetail(accessToken: String, profileId: String, id: String): JsonObject {
            details++; return movie(profileId, id)
        }
        override suspend fun movieTicket(accessToken: String, profileId: String, movieId: String): PlaybackTicket {
            tickets++; error("Browsing must never open a video session")
        }
    }

    @Test fun bulkGenresRepairOldCacheWithoutOpeningDetailsAndRetryPreservesMetadataAndRanking() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = Gateway()
        val tokens = object : TokenStore {
            override fun load() = AuthTokens("test-only")
            override fun save(tokens: AuthTokens) = Unit
            override fun clear() = Unit
        }
        val repository = CatalogRepository(gateway, AuthCoordinator(gateway, tokens, scope), db, scope)
        try {
            // A freshly checked 0.5.0 cache must still be repaired on upgrade.
            db.catalogDao().saveSync(CatalogSyncEntity("a", System.currentTimeMillis(), System.currentTimeMillis()))
            assertTrue(repository.needsRefresh("a"))
            assertTrue(runCatching { repository.refresh("a") }.isFailure)
            val partial = repository.titles("a").first()
            assertEquals(2, partial.size)
            assertEquals(listOf("Märul"), partial.first { it.id == "one" }.genres)
            assertTrue(repository.needsRefresh("a"))
            gateway.interrupted = false
            repository.refresh("a")
            assertFalse(repository.needsRefresh("a"))
            val items = repository.titles("a").first()
            assertTrue(items.all { it.genres.isNotEmpty() && it.description == "Keep description" && it.originalTitle == "Original ${it.id}" })
            assertEquals(setOf("Märul", "Draama"), CatalogUiState(titles = items).rows().filter { it.key.startsWith("genre:") }.map { it.title }.toSet())
            assertEquals(listOf("two", "one"), repository.popularMovieIds("a").first())
            assertEquals(0, gateway.details)
            assertEquals(0, gateway.tickets)
            gateway.rankingOffline = true
            repository.refresh("a")
            assertEquals(listOf("two", "one"), repository.popularMovieIds("a").first())
            repository.refreshRights("a")
            assertFalse(repository.needsRefresh("a"))
            assertEquals(listOf("two", "one"), repository.popularMovieIds("a").first())
            assertTrue(repository.titles("b").first().isEmpty())
            assertTrue(repository.popularMovieIds("b").first().isEmpty())
            assertTrue(repository.needsRefresh("b"))
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }
    }
}
