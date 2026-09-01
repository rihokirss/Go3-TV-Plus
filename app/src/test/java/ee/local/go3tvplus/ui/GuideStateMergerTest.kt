package ee.local.go3tvplus.ui

import ee.local.go3tvplus.domain.Channel
import ee.local.go3tvplus.domain.DeviceAuthState
import ee.local.go3tvplus.domain.Program
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GuideStateMergerTest {
    @Test fun guideRefreshPreservesProfileAndPlaybackRestoredWhileIndexing() {
        val channel = Channel(id = "channel", name = "Channel", serverNumber = 1)
        val latest = TvUiState(
            auth = DeviceAuthState.Approved,
            selectedProfileId = "restored-profile",
            channels = listOf(channel),
            currentChannelId = channel.id,
            loading = true,
            videoVisible = true,
            overlay = Overlay.SEEK,
        )
        val indexedPrograms = mapOf(channel.id to emptyList<Program>())

        val merged = GuideStateMerger.merge(latest, indexedPrograms)

        assertEquals("restored-profile", merged.selectedProfileId)
        assertEquals(channel.id, merged.currentChannelId)
        assertEquals(true, merged.loading)
        assertEquals(true, merged.videoVisible)
        assertEquals(Overlay.SEEK, merged.overlay)
        assertSame(indexedPrograms, merged.programsByChannel)
    }
}
