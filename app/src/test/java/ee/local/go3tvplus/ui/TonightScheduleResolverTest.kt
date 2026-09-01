package ee.local.go3tvplus.ui

import ee.local.go3tvplus.domain.Channel
import ee.local.go3tvplus.domain.Program
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TonightScheduleResolverTest {
    private val zone = ZoneId.of("Europe/Tallinn")

    @Test fun daytimeWindowStartsAtSevenPm() {
        val now = Instant.parse("2026-09-01T10:00:00Z") // 13:00 kohalik
        assertEquals(
            Instant.parse("2026-09-01T16:00:00Z"), // 19:00 kohalik
            TonightScheduleResolver.windowStart(now, zone),
        )
    }

    @Test fun eveningAndNightWindowsStartNow() {
        val evening = Instant.parse("2026-09-01T18:30:00Z") // 21:30 kohalik
        assertEquals(evening, TonightScheduleResolver.windowStart(evening, zone))
        val night = Instant.parse("2026-09-01T22:30:00Z") // 01:30 kohalik
        assertEquals(night, TonightScheduleResolver.windowStart(night, zone))
    }

    @Test fun favoritesLimitChannelsAndFallbackUsesAll() {
        val channels = listOf(channel("a", 1), channel("b", 2))
        val programs = mapOf(
            "a" to listOf(program("pa", "a", "2026-09-01T17:00:00Z", "2026-09-01T18:00:00Z")),
            "b" to listOf(program("pb", "b", "2026-09-01T17:00:00Z", "2026-09-01T18:00:00Z")),
        )
        val now = Instant.parse("2026-09-01T10:00:00Z")
        assertEquals(
            listOf("pb"),
            TonightScheduleResolver.entries(channels, setOf("b"), programs, now, zone).map { it.program.id },
        )
        assertEquals(
            listOf("pa", "pb"),
            TonightScheduleResolver.entries(channels, emptySet(), programs, now, zone).map { it.program.id },
        )
    }

    @Test fun entriesKeepRunningAndUpcomingButDropEndedAndTooLate() {
        val channels = listOf(channel("a", 1))
        val now = Instant.parse("2026-09-01T18:00:00Z") // 21:00 kohalik -> aken [now, now+5h]
        val programs = mapOf(
            "a" to listOf(
                program("ended", "a", "2026-09-01T16:00:00Z", "2026-09-01T17:30:00Z"),
                program("running", "a", "2026-09-01T17:30:00Z", "2026-09-01T18:30:00Z"),
                program("upcoming", "a", "2026-09-01T19:00:00Z", "2026-09-01T20:00:00Z"),
                program("tooLate", "a", "2026-09-02T00:00:00Z", "2026-09-02T01:00:00Z"),
            ),
        )
        assertEquals(
            listOf("running", "upcoming"),
            TonightScheduleResolver.entries(channels, emptySet(), programs, now, zone).map { it.program.id },
        )
    }

    @Test fun entriesSortByStartTimeThenChannelNumber() {
        val channels = listOf(channel("b", 2), channel("a", 1))
        val programs = mapOf(
            "a" to listOf(program("late", "a", "2026-09-01T18:00:00Z", "2026-09-01T19:00:00Z")),
            "b" to listOf(
                program("early", "b", "2026-09-01T17:00:00Z", "2026-09-01T18:00:00Z"),
                program("sameStart", "b", "2026-09-01T18:00:00Z", "2026-09-01T19:00:00Z"),
            ),
        )
        val now = Instant.parse("2026-09-01T10:00:00Z")
        assertEquals(
            listOf("early", "late", "sameStart"),
            TonightScheduleResolver.entries(channels, emptySet(), programs, now, zone).map { it.program.id },
        )
    }

    private fun channel(id: String, number: Int?) = Channel(id = id, name = id, serverNumber = number)

    private fun program(id: String, channelId: String, start: String, end: String) = Program(
        id = id,
        channelId = channelId,
        title = id,
        startsAt = Instant.parse(start),
        endsAt = Instant.parse(end),
        catchupAvailable = false,
    )
}
