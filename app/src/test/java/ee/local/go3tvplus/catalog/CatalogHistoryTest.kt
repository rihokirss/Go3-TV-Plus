package ee.local.go3tvplus.catalog

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CatalogHistoryTest {
    @Test fun capturedBookmarkKeepsSecondsProfileIndependentMetadataAndServerTime() {
        val root = Json.parseToJsonElement("""{"items":[{"item":{"id":4,"type":"EPISODE","title":"Episode","duration":100,"status":{"bookmark":64.5,"progressWatching":64},"season":{"id":3,"serial":{"id":2,"title":"Series","type":"SERIAL"}}},"playTime":64.5,"createdAt":"2026-11-12T10:00:00Z","modifiedAt":"2026-11-12T11:00:00Z","watchedAt":null}],"continueWatchingItems":[]}""").jsonObject
        val record = parseCatalogHistory(root).single()
        assertEquals("4", record.id); assertEquals("2", record.seriesId); assertEquals("3", record.seasonId)
        assertEquals(64500L, record.positionMs); assertEquals(100000L, record.durationMs)
        assertEquals(Instant.parse("2026-11-12T11:00:00Z").toEpochMilli(), record.updatedAt)
        assertFalse(record.completed)
    }
    @Test fun explicitGo3WatchedStateIsPreservedWithoutInventingAPlaybackPosition() {
        val root = Json.parseToJsonElement("""{"items":[{"item":{"id":1,"type":"VOD","title":"Movie","duration":100,"status":{"bookmark":96}},"modifiedAt":"2026-11-12T11:00:00Z","watchedAt":"2026-11-12T11:00:00Z"}]}""").jsonObject
        val record = parseCatalogHistory(root).single()
        assertTrue(record.completed); assertEquals(96000L, record.positionMs)
        assertTrue(CatalogProgress("a", "1", null, 0, 0, 0, completed = true).watched)
    }
    @Test fun liveTvAndMalformedPositionsDoNotBecomeCatalogueProgress() {
        val root = Json.parseToJsonElement("""{"items":[{"item":{"id":1,"type":"LIVE","status":{"bookmark":5}}},{"item":{"id":2,"type":"VOD","status":{"bookmark":-1}}},{"item":{"id":3,"type":"VOD"}}]}""").jsonObject
        assertTrue(parseCatalogHistory(root).isEmpty())
    }
    @Test fun newestChangeWinsIncludingRewindsAndPendingLocalChangesHaveClockTolerance() {
        assertTrue(remoteHistoryWins(100, null, false))
        assertFalse(remoteHistoryWins(99, 100, false))
        assertTrue(remoteHistoryWins(101, 100, false))
        assertFalse(remoteHistoryWins(5000, 100, true))
        assertTrue(remoteHistoryWins(5101, 100, true))
    }
}
