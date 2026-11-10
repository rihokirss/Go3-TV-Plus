package ee.local.go3tvplus.data

import ee.local.go3tvplus.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WatchNowMatchCodecTest {
    private val now = Instant.parse("2026-11-10T20:00:00Z")
    private val program = Program("future", "tv6", "Film", startsAt = now.plusSeconds(86400), endsAt = now.plusSeconds(90000),
        catchupAvailable = false, secondaryId = "identity", epgProvider = "T24")
    private val movie = WatchNowSource.Movie(CatalogMovie("movie", "identity", "Film", null, 6000, now.minusSeconds(3600), now.plusSeconds(7200)))
    private val recording = WatchNowSource.Recording(program.copy(id = "recording", channelId = "tv3",
        startsAt = now.minusSeconds(7200), endsAt = now.minusSeconds(3600), catchupAvailable = true, catchupUntil = now.plusSeconds(3600)))

    @Test fun persistedMovieAndEpisodeRestoreWithTheirOriginalValidity() {
        val row = WatchNowMatchCodec.encode("profile", program, listOf(movie, recording), now)
        assertEquals("profile", row.profileId)
        assertEquals(now.toEpochMilli(), row.checkedAtEpochMs)
        assertEquals(listOf(movie, recording), WatchNowMatchCodec.decode(row, program, setOf("tv3", "tv6"), now))
        assertEquals(listOf(movie), WatchNowMatchCodec.decode(row, program, setOf("tv6"), now))
        assertEquals(listOf(movie), WatchNowMatchCodec.decode(row, program, setOf("tv3"), now.plusSeconds(3601)))
        assertTrue(WatchNowMatchCodec.decode(row, program, setOf("tv3"), now.plusSeconds(7201)).isEmpty())
    }

    @Test fun descriptionsCanChangeButADifferentEpisodeOrScheduleCannotUseSavedMatches() {
        val row = WatchNowMatchCodec.encode("profile", program, listOf(movie), now)
        assertEquals(listOf(movie), WatchNowMatchCodec.decode(row, program.copy(description = "New synopsis"), emptySet(), now))
        for (changed in listOf(program.copy(secondaryId = "other"), program.copy(epgProvider = "other"),
            program.copy(title = "Remake"), program.copy(channelId = "other"), program.copy(startsAt = now.plusSeconds(1)))) {
            assertTrue(WatchNowMatchCodec.decode(row, changed, setOf("tv3"), now).isEmpty())
        }
        assertTrue(WatchNowMatchCodec.decode(row, program, setOf("tv3"), program.startsAt).isEmpty())
    }

    @Test fun corruptAndUnknownStoredSourcesAreIgnored() {
        val row = WatchNowMatchCodec.encode("profile", program, listOf(movie), now)
        assertTrue(WatchNowMatchCodec.decode(row.copy(sourcesJson = "invalid"), program, emptySet(), now).isEmpty())
        assertTrue(WatchNowMatchCodec.decode(row.copy(sourcesJson = "[{\"kind\":\"unknown\"}]"), program, emptySet(), now).isEmpty())
    }
}
