package ee.local.go3tvplus.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

object ProgramWindow {
    fun isCurrent(program: Program, at: Instant): Boolean =
        !at.isBefore(program.startsAt) && at.isBefore(program.endsAt)

    fun overlaps(program: Program, from: Instant, until: Instant): Boolean =
        program.endsAt.isAfter(from) && program.startsAt.isBefore(until)

    fun durationMinutes(program: Program): Long =
        Duration.between(program.startsAt, program.endsAt).toMinutes().coerceAtLeast(0)

    fun guideWindowStart(anchor: Instant, zoneId: ZoneId): Instant =
        anchor.minus(Duration.ofMinutes(30))
            .atZone(zoneId)
            .withMinute(0)
            .withSecond(0)
            .withNano(0)
            .toInstant()
}
