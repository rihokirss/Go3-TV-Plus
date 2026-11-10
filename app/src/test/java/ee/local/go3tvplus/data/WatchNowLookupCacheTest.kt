package ee.local.go3tvplus.data

import ee.local.go3tvplus.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WatchNowLookupCacheTest {
    private val start = Instant.parse("2026-11-10T20:00:00Z")
    private val program = Program("future", "tv3", "Film", null, start.plusSeconds(86400), start.plusSeconds(90000), true)
    private val movie = WatchNowSource.Movie(CatalogMovie("movie", "epg", "Film", null, 6000, start.minusSeconds(100), null))
    private val channels = setOf("tv3", "tv6")

    @Test fun negativeResultsAvoidRepeatedLookupsButExpireSoonerThanMatches() {
        var at = start
        val cache = WatchNowLookupCache { at }
        cache.put("profile", program, channels, emptyList())
        assertEquals(emptyList<WatchNowSource>(), cache.get("profile", program, channels))
        at = start.plusSeconds(61)
        assertNull(cache.get("profile", program, channels))
        cache.put("profile", program, channels, listOf(movie))
        at = at.plusSeconds(61)
        assertEquals(listOf(movie), cache.get("profile", program, channels))
        at = at.plusSeconds(300)
        assertNull(cache.get("profile", program, channels))
    }

    @Test fun changedProfileEpisodeMetadataAndPermissionsDoNotReuseResults() {
        val cache = WatchNowLookupCache { start }
        cache.put("profile", program, channels, listOf(movie))
        assertNull(cache.get("kids", program, channels))
        assertNull(cache.get("profile", program.copy(secondaryId = "different-episode"), channels))
        assertNull(cache.get("profile", program, setOf("tv3")))
    }

    @Test fun harmlessScheduleUpdatesReuseTheResultAndRestoredTimestampsDoNotBecomeFresh() {
        val cache = WatchNowLookupCache { start }
        cache.put("profile", program, channels, listOf(movie))
        assertEquals(listOf(movie), cache.get("profile", program.copy(description = "New synopsis", catchupAvailable = false), channels))
        cache.put("profile", program, channels, listOf(movie), start.minusSeconds(301))
        assertNull(cache.get("profile", program, channels))
    }

    @Test fun boundedCacheEvictsTheLeastRecentlyUsedEntry() {
        val cache = WatchNowLookupCache { start }
        for (i in 0 until WatchNowLookupCache.MAX_ENTRIES) cache.put("profile", program.copy(id = "$i"), channels, emptyList())
        assertNotNull(cache.get("profile", program.copy(id = "0"), channels))
        cache.put("profile", program.copy(id = "new"), channels, emptyList())
        assertNull(cache.get("profile", program.copy(id = "1"), channels))
        assertNotNull(cache.get("profile", program.copy(id = "0"), channels))
    }
}
