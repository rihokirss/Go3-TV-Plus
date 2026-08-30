package ee.local.go3tvplus.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class ProgramWindowTest {
    @Test fun endBoundaryBelongsToNextProgram() {
        val start = Instant.parse("2026-08-29T10:00:00Z")
        val program = program(start, start.plusSeconds(3600))
        assertTrue(ProgramWindow.isCurrent(program, start))
        assertFalse(ProgramWindow.isCurrent(program, program.endsAt))
    }

    @Test fun durationUsesInstantsAcrossTallinnDstJump() {
        val zone = ZoneId.of("Europe/Tallinn")
        val start = ZonedDateTime.of(2026, 3, 29, 2, 30, 0, 0, zone).toInstant()
        val end = ZonedDateTime.of(2026, 3, 29, 4, 30, 0, 0, zone).toInstant()
        assertEquals(60, ProgramWindow.durationMinutes(program(start, end)))
    }

    @Test fun overlapExcludesTouchingEdges() {
        val start = Instant.parse("2026-08-29T10:00:00Z")
        val program = program(start, start.plusSeconds(3600))
        assertFalse(ProgramWindow.overlaps(program, program.endsAt, program.endsAt.plusSeconds(1)))
        assertTrue(ProgramWindow.overlaps(program, start.plusSeconds(1800), program.endsAt.plusSeconds(1)))
    }

    @Test fun guideWindowStartsOnAFullLocalHourWithContextBeforeAnchor() {
        val zone = ZoneId.of("Europe/Tallinn")
        val anchor = ZonedDateTime.of(2026, 8, 30, 20, 18, 0, 0, zone).toInstant()

        val start = ProgramWindow.guideWindowStart(anchor, zone).atZone(zone)

        assertEquals(19, start.hour)
        assertEquals(0, start.minute)
        assertEquals(0, start.second)
    }

    private fun program(start: Instant, end: Instant) = Program(
        id = "p",
        channelId = "c",
        title = "Saade",
        startsAt = start,
        endsAt = end,
        catchupAvailable = true,
    )
}
