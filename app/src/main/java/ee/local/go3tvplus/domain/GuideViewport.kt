package ee.local.go3tvplus.domain

import java.time.Instant

/** Shared by rendering and prefetching so only the six rows actually on screen are scanned. */
object GuideViewport {
    const val CHANNEL_ROWS = 6

    fun firstChannelIndex(selectedIndex: Int, channelCount: Int): Int =
        (selectedIndex - 2).coerceIn(0, (channelCount - CHANNEL_ROWS).coerceAtLeast(0))

    fun upcomingPrograms(
        channels: List<Channel>,
        programsByChannel: Map<String, List<Program>>,
        selectedIndex: Int,
        windowStart: Instant,
        now: Instant,
    ): List<Program> {
        val end = windowStart.plus(ProgramWindow.GUIDE_WINDOW_DURATION)
        return channels.drop(firstChannelIndex(selectedIndex, channels.size)).take(CHANNEL_ROWS)
            .flatMap { programsByChannel[it.id].orEmpty() }
            .filter { it.startsAt.isAfter(now) && ProgramWindow.overlaps(it, windowStart, end) }
            .distinctBy(Program::id)
    }
}
