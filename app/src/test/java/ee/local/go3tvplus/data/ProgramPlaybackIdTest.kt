package ee.local.go3tvplus.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgramPlaybackIdTest {
    @Test
    fun `catalog recording id is used for catchup playback`() {
        assertEquals("12897015", resolveProgramPlaybackId("12233523", "12897015"))
    }

    @Test
    fun `legacy programme id remains a safe fallback`() {
        assertEquals("12233523", resolveProgramPlaybackId("12233523", null))
        assertEquals("12233523", resolveProgramPlaybackId("12233523", ""))
    }
}
