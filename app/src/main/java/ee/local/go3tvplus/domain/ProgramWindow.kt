package ee.local.go3tvplus.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

object ProgramWindow {
    enum class GuideSelectionAction { TUNE_LIVE, PLAY_CATCHUP, SHOW_INFO }

    fun isCurrent(program: Program, at: Instant): Boolean =
        !at.isBefore(program.startsAt) && at.isBefore(program.endsAt)

    fun guideSelectionAction(program: Program?, at: Instant): GuideSelectionAction = when {
        program == null -> GuideSelectionAction.TUNE_LIVE
        program.startsAt.isAfter(at) -> GuideSelectionAction.SHOW_INFO
        !program.endsAt.isAfter(at) -> GuideSelectionAction.PLAY_CATCHUP
        else -> GuideSelectionAction.TUNE_LIVE
    }

    fun overlaps(program: Program, from: Instant, until: Instant): Boolean =
        program.endsAt.isAfter(from) && program.startsAt.isBefore(until)

    fun durationMinutes(program: Program): Long =
        Duration.between(program.startsAt, program.endsAt).toMinutes().coerceAtLeast(0)

    fun guideWindowStart(anchor: Instant, zoneId: ZoneId): Instant =
        anchor.minus(GUIDE_WINDOW_STEP).atZone(zoneId).let { localAnchor ->
            localAnchor
                .withMinute(if (localAnchor.minute < 30) 0 else 30)
                .withSecond(0)
                .withNano(0)
                .toInstant()
        }

    fun guideWindowStartKeepingVisible(
        currentWindowStart: Instant,
        program: Program,
    ): Instant {
        if (!program.endsAt.isAfter(program.startsAt)) return currentWindowStart
        var windowStart = currentWindowStart
        // Mere overlap can leave only a sliver of the selected cell at the edge.
        // Reserve one grid step for alignment; unusually long programmes keep their title/start visible.
        val visibleEnd = minOf(program.endsAt, program.startsAt.plus(GUIDE_WINDOW_DURATION.minus(GUIDE_WINDOW_STEP)))
        val stepSeconds = GUIDE_WINDOW_STEP.seconds
        fun stepsFor(duration: Duration): Long = duration.seconds / stepSeconds +
            if (duration.seconds % stepSeconds != 0L || duration.nano != 0) 1 else 0
        if (program.startsAt.isBefore(windowStart)) {
            windowStart = windowStart.minus(GUIDE_WINDOW_STEP.multipliedBy(stepsFor(Duration.between(program.startsAt, windowStart))))
        }
        val windowEnd = windowStart.plus(GUIDE_WINDOW_DURATION)
        if (visibleEnd.isAfter(windowEnd)) {
            windowStart = windowStart.plus(GUIDE_WINDOW_STEP.multipliedBy(stepsFor(Duration.between(windowEnd, visibleEnd))))
        }
        return windowStart
    }

    val GUIDE_WINDOW_DURATION: Duration = Duration.ofHours(4)
    val GUIDE_WINDOW_STEP: Duration = Duration.ofMinutes(30)

    fun boundaryFractions(
        programs: List<Program>,
        timelineStart: Instant,
        timelineDurationMs: Long,
    ): List<Float> {
        if (timelineDurationMs <= 0L) return emptyList()
        val timelineEnd = timelineStart.plusMillis(timelineDurationMs)
        return programs.asSequence()
            .map(Program::startsAt)
            .distinct()
            .filter { it.isAfter(timelineStart) && it.isBefore(timelineEnd) }
            .map { boundary ->
                Duration.between(timelineStart, boundary).toMillis().toFloat() / timelineDurationMs.toFloat()
            }
            .toList()
    }

    fun deduplicateSchedule(programs: List<Program>): List<Program> {
        val latestBySlot = LinkedHashMap<ScheduleSlot, Program>()
        programs.forEach { program ->
            latestBySlot[ScheduleSlot(program.channelId, program.startsAt, program.endsAt)] = program
        }
        return latestBySlot.values.sortedWith(compareBy(Program::channelId, Program::startsAt))
    }

    private data class ScheduleSlot(
        val channelId: String,
        val startsAt: Instant,
        val endsAt: Instant,
    )
}
