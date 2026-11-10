package ee.local.go3tvplus.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class WatchNowCatalogTest {
    private fun entry(type: String = "VOD", active: Boolean = true, duration: String = "7521", externalId: String = "80050706") =
        Json.parseToJsonElement("""{"id":1212295,"externalUid":"$externalId","title":"John Wick 3","originalTitle":"John Wick: Chapter 3","type":"$type","active":$active,"duration":$duration,"since":"2026-01-01T00:00:00Z","till":"2027-01-01T00:00:00Z"}""").jsonObject

    @Test fun numericIdsAndDurationAreParsedWithoutLosingIdentity() {
        val movie = entry().toCatalogMovie()!!
        assertEquals("1212295", movie.id)
        assertEquals("80050706", movie.externalId)
        assertEquals(7521L, movie.durationSeconds)
    }

    @Test fun seriesContainersAndTrailersAreNotFullMovies() {
        assertNull(entry(type = "SERIAL").toCatalogMovie())
        assertNull(entry(type = "CLIP").toCatalogMovie())
        assertNull(entry(active = false).toCatalogMovie())
        assertNull(entry(duration = "null").toCatalogMovie())
        assertNull(entry(externalId = "").toCatalogMovie())
    }
}
