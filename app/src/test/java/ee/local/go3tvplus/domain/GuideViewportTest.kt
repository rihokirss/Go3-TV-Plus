package ee.local.go3tvplus.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class GuideViewportTest {
    private val now = Instant.parse("2026-11-10T20:00:00Z")
    private val channels = (0..9).map { Channel("$it", "Channel $it") }
    private fun program(id: String, channel: String, start: Long, end: Long) =
        Program(id, channel, id, null, now.plusSeconds(start), now.plusSeconds(end), true)

    @Test fun prefetchMatchesExactlyTheSixRenderedRows() {
        val programs = channels.associate { it.id to listOf(program(it.id, it.id, 3600, 7200)) }
        assertEquals(listOf("2", "3", "4", "5", "6", "7"),
            GuideViewport.upcomingPrograms(channels, programs, 4, now, now).map { it.channelId })
        assertEquals(0, GuideViewport.firstChannelIndex(0, 10))
        assertEquals(4, GuideViewport.firstChannelIndex(9, 10))
        assertEquals(0, GuideViewport.firstChannelIndex(2, 3))
    }

    @Test fun clipsToTheVisibleTimeWindowAndExcludesLiveAndPastPrograms() {
        val programs = listOf(
            program("past", "0", -7200, -3600), program("live", "0", -600, 600),
            program("before-window", "0", 600, 1800), program("overlapping", "0", 1800, 5400),
            program("visible", "0", 7200, 10800), program("after-window", "0", 18000, 20000),
        )
        assertEquals(listOf("overlapping", "visible"),
            GuideViewport.upcomingPrograms(channels, mapOf("0" to programs), 0, now.plusSeconds(3600), now).map(Program::id))
    }

    @Test fun favouriteFilterAndEmptyGuideDoNotScanHiddenRows() {
        val programs = channels.associate { it.id to listOf(program(it.id, it.id, 3600, 7200)) }
        assertEquals(listOf("1", "9"), GuideViewport.upcomingPrograms(listOf(channels[1], channels[9]), programs, 0, now, now).map(Program::id))
        assertTrue(GuideViewport.upcomingPrograms(emptyList(), programs, 0, now, now).isEmpty())
    }
}
