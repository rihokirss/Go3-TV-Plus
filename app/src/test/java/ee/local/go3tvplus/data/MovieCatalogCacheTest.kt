package ee.local.go3tvplus.data

import ee.local.go3tvplus.domain.CatalogMovie
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MovieCatalogCacheTest {
    private val start = Instant.parse("2026-11-10T20:00:00Z")
    private fun movie(profile: String) = CatalogMovie(profile, "epg-id", "Film", null, 6000, start, null)

    @Test fun simultaneousLookupsShareACatalogueAndCacheMisses() = runTest {
        var loads = 0
        val cache = MovieCatalogCache(backgroundScope, { profile -> loads++; delay(100); listOf(movie(profile)) }, { start })
        val first = async { cache.candidates("profile", "epg-id") }
        val second = async { cache.candidates("profile", "absent") }
        assertEquals("profile", first.await().single().id)
        assertTrue(second.await().isEmpty())
        assertEquals(1, loads)
    }

    @Test fun profileSwitchAndExpiryReloadTheCatalogue() = runTest {
        var at = start
        var loads = 0
        val cache = MovieCatalogCache(backgroundScope, { profile -> loads++; listOf(movie(profile)) }, { at })
        cache.candidates("one", "epg-id")
        assertEquals("two", cache.candidates("two", "epg-id").single().id)
        assertEquals(2, loads)
        at = start.plusSeconds(901)
        cache.candidates("two", "epg-id")
        assertEquals(3, loads)
    }

    @Test fun failedOrCancelledLoadIsNotPublishedAsAnEmptyCatalogue() = runTest {
        var attempt = 0
        val cache = MovieCatalogCache(backgroundScope, { profile ->
            when (++attempt) {
                1 -> throw IllegalStateException("network")
                2 -> throw CancellationException("selection changed")
                else -> listOf(movie(profile))
            }
        }, { start })
        try { cache.candidates("one", "epg-id"); fail("Expected failure") } catch (_: IllegalStateException) { }
        try { cache.candidates("one", "epg-id"); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(1, cache.candidates("one", "epg-id").size)
        assertEquals(3, attempt)
    }

    @Test fun changingSelectionReusesTheDownloadAlreadyInProgress() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var loads = 0
        val cache = MovieCatalogCache(backgroundScope, { profile ->
            loads++; started.complete(Unit); finish.await(); listOf(movie(profile))
        }, { start })
        val first = async { cache.candidates("one", "old-selection") }
        started.await()
        first.cancelAndJoin()
        val second = async { cache.candidates("one", "epg-id") }
        finish.complete(Unit)
        assertEquals(1, second.await().size)
        assertEquals(1, loads)
    }

    @Test fun aViewportOfIdentitiesUsesOneCatalogueDownload() = runTest {
        var loads = 0
        val cache = MovieCatalogCache(backgroundScope, { profile -> loads++; listOf(movie(profile)) }, { start })
        val batch = cache.candidates("one", setOf("epg-id", "unmatched", "another"))
        assertEquals(1, batch["epg-id"]!!.size)
        assertTrue(batch["unmatched"]!!.isEmpty())
        cache.candidates("one", setOf("another-viewport", "epg-id"))
        assertEquals(1, loads)
    }
}
