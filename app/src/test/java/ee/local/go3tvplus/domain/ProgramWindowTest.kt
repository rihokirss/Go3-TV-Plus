package ee.local.go3tvplus.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
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

    @Test fun guideSelectionSeparatesFutureLiveAndCatchup() {
        val start = Instant.parse("2026-08-29T10:00:00Z")
        val item = program(start, start.plusSeconds(3600))

        assertEquals(
            ProgramWindow.GuideSelectionAction.SHOW_INFO,
            ProgramWindow.guideSelectionAction(item, start.minusMillis(1)),
        )
        assertEquals(
            ProgramWindow.GuideSelectionAction.TUNE_LIVE,
            ProgramWindow.guideSelectionAction(item, start),
        )
        assertEquals(
            ProgramWindow.GuideSelectionAction.TUNE_LIVE,
            ProgramWindow.guideSelectionAction(item, item.endsAt.minusMillis(1)),
        )
        assertEquals(
            ProgramWindow.GuideSelectionAction.PLAY_CATCHUP,
            ProgramWindow.guideSelectionAction(item, item.endsAt),
        )
        assertEquals(
            ProgramWindow.GuideSelectionAction.TUNE_LIVE,
            ProgramWindow.guideSelectionAction(null, start),
        )
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

    @Test fun guideWindowKeepsHalfAnHourOfContextAndRoundsDownToHalfHour() {
        val zone = ZoneId.of("Europe/Tallinn")
        val anchor = ZonedDateTime.of(2026, 8, 30, 20, 18, 0, 0, zone).toInstant()

        val start = ProgramWindow.guideWindowStart(anchor, zone).atZone(zone)

        assertEquals(19, start.hour)
        assertEquals(30, start.minute)
        assertEquals(0, start.second)

        val laterAnchor = ZonedDateTime.of(2026, 8, 30, 20, 47, 0, 0, zone).toInstant()
        val laterStart = ProgramWindow.guideWindowStart(laterAnchor, zone).atZone(zone)
        assertEquals(20, laterStart.hour)
        assertEquals(0, laterStart.minute)

        val userExample = ZonedDateTime.of(2026, 8, 30, 15, 23, 0, 0, zone).toInstant()
        val exampleStart = ProgramWindow.guideWindowStart(userExample, zone).atZone(zone)
        assertEquals(14, exampleStart.hour)
        assertEquals(30, exampleStart.minute)
    }

    @Test fun guideWindowStaysStillWhileNextProgramIsVisible() {
        val zone = ZoneId.of("Europe/Tallinn")
        val windowStart = ZonedDateTime.of(2026, 8, 30, 19, 0, 0, 0, zone).toInstant()
        val visibleProgram = program(
            ZonedDateTime.of(2026, 8, 30, 21, 0, 0, 0, zone).toInstant(),
            ZonedDateTime.of(2026, 8, 30, 22, 0, 0, 0, zone).toInstant(),
        )

        assertEquals(
            windowStart,
            ProgramWindow.guideWindowStartKeepingVisible(windowStart, visibleProgram),
        )
    }

    @Test fun guideWindowMovesEnoughToShowTheWholeSelectedProgram() {
        val zone = ZoneId.of("Europe/Tallinn")
        val windowStart = ZonedDateTime.of(2026, 8, 30, 19, 0, 0, 0, zone).toInstant()
        val outsideProgram = program(
            ZonedDateTime.of(2026, 8, 30, 23, 0, 0, 0, zone).toInstant(),
            ZonedDateTime.of(2026, 8, 31, 0, 0, 0, 0, zone).toInstant(),
        )

        val movedStart = ProgramWindow.guideWindowStartKeepingVisible(windowStart, outsideProgram)

        assertEquals(20, movedStart.atZone(zone).hour)
        assertEquals(0, movedStart.atZone(zone).minute)
    }

    @Test fun partlyVisibleRightEdgeProgramDoesNotRemainClipped() {
        val start = Instant.parse("2026-11-10T19:00:00Z")
        val selected = program(start.plusSeconds(3 * 3600 + 45 * 60), start.plusSeconds(4 * 3600 + 45 * 60))
        val moved = ProgramWindow.guideWindowStartKeepingVisible(start, selected)
        assertEquals(start.plusSeconds(3600), moved)
        assertFalse(selected.startsAt.isBefore(moved))
        assertFalse(selected.endsAt.isAfter(moved.plus(ProgramWindow.GUIDE_WINDOW_DURATION)))
    }

    @Test fun partlyVisibleLeftEdgeProgramKeepsItsTitleAndStartVisible() {
        val start = Instant.parse("2026-11-10T19:00:00Z")
        val selected = program(start.minusSeconds(900), start.plusSeconds(900))
        assertEquals(start.minusSeconds(1800), ProgramWindow.guideWindowStartKeepingVisible(start, selected))
    }

    @Test fun longProgrammesAndAWeekJumpCannotPushTheTitleOutOfView() {
        val start = Instant.parse("2026-11-10T19:00:00Z")
        for (offset in listOf(-7 * 86400L + 13 * 60, -3600L, 3600L, 7 * 86400L + 13 * 60)) {
            val selected = program(start.plusSeconds(offset), start.plusSeconds(offset + 6 * 3600))
            val moved = ProgramWindow.guideWindowStartKeepingVisible(start, selected)
            assertFalse(selected.startsAt.isBefore(moved))
            assertTrue(selected.startsAt.isBefore(moved.plus(ProgramWindow.GUIDE_WINDOW_DURATION)))
            assertEquals(0L, Duration.between(start, moved).seconds % 1800)
        }
    }

    @Test fun sequentialRightAndLeftNavigationAlwaysShowsShortProgrammesInFull() {
        val start = Instant.parse("2026-11-10T19:00:00Z")
        val programs = (0..40).map { index ->
            val since = start.plusSeconds(index * 2700L + 13 * 60)
            program(since, since.plusSeconds(if (index % 3 == 0) 9000 else 2700))
        }
        var window = start
        for (selected in programs + programs.reversed()) {
            window = ProgramWindow.guideWindowStartKeepingVisible(window, selected)
            assertFalse(selected.startsAt.isBefore(window))
            assertFalse(selected.endsAt.isAfter(window.plus(ProgramWindow.GUIDE_WINDOW_DURATION)))
        }
    }

    @Test fun programmeBoundariesArePositionedInsidePlaybackTimeline() {
        val timelineStart = Instant.parse("2026-08-30T16:00:00Z")
        val programs = listOf(
            program(timelineStart, timelineStart.plusSeconds(3_600)),
            program(timelineStart.plusSeconds(3_600), timelineStart.plusSeconds(7_200)),
            program(timelineStart.plusSeconds(7_200), timelineStart.plusSeconds(10_800)),
        )

        val fractions = ProgramWindow.boundaryFractions(
            programs,
            timelineStart,
            Duration.ofHours(4).toMillis(),
        )

        assertEquals(listOf(0.25f, 0.5f), fractions)
    }

    @Test fun duplicateScheduleSlotsKeepTheLatestPlaybackId() {
        val start = Instant.parse("2026-08-30T16:00:00Z")
        val old = program(start, start.plusSeconds(3_600))
        val fresh = old.copy(id = "fresh-recording-id", catchupAvailable = true)
        val next = program(start.plusSeconds(3_600), start.plusSeconds(7_200)).copy(id = "next")

        val deduplicated = ProgramWindow.deduplicateSchedule(listOf(old, fresh, next))

        assertEquals(listOf("fresh-recording-id", "next"), deduplicated.map(Program::id))
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
