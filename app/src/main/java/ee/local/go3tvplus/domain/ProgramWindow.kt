package ee.local.go3tvplus.domain

import java.time.Duration
import java.time.Instant

object ProgramWindow {
    fun isCurrent(program: Program, at: Instant): Boolean =
        !at.isBefore(program.startsAt) && at.isBefore(program.endsAt)

    fun overlaps(program: Program, from: Instant, until: Instant): Boolean =
        program.endsAt.isAfter(from) && program.startsAt.isBefore(until)

    fun durationMinutes(program: Program): Long =
        Duration.between(program.startsAt, program.endsAt).toMinutes().coerceAtLeast(0)
}

