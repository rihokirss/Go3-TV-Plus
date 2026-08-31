package ee.local.go3tvplus.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RemoteShortcutResolverTest {
    @Test fun zeroWithoutPendingDigitsUsesPreviousChannel() {
        assertTrue(RemoteShortcutResolver.usesPreviousChannel(0, "", Overlay.NONE))
    }

    @Test fun zeroAfterAnotherDigitRemainsPartOfChannelNumber() {
        assertFalse(RemoteShortcutResolver.usesPreviousChannel(0, "8", Overlay.NONE))
    }

    @Test fun zeroRemainsAvailableWhenEditingChannelNumbers() {
        assertFalse(RemoteShortcutResolver.usesPreviousChannel(0, "", Overlay.CHANNEL_SETTINGS))
    }

    @Test fun successfulTunesSwapThePreviousChannel() {
        val afterB = PreviousChannelResolver.afterSuccessfulTune(null, "a", "b")
        val afterA = PreviousChannelResolver.afterSuccessfulTune(afterB, "b", "a")

        assertEquals("a", afterB)
        assertEquals("b", afterA)
    }

    @Test fun retuningSameChannelKeepsHistory() {
        assertEquals("a", PreviousChannelResolver.afterSuccessfulTune("a", "b", "b"))
    }

    @Test fun startOverUsesLiveBufferWhenProgrammeStartIsSeekable() {
        val now = Instant.parse("2026-08-31T17:00:00Z")

        assertEquals(
            -3_600_000L,
            StartOverResolver.liveRewindMs(4 * 3_600_000L, now.minusSeconds(3_600), now),
        )
    }

    @Test fun startOverFallsBackWhenProgrammeStartPrecedesLiveBuffer() {
        val now = Instant.parse("2026-08-31T17:00:00Z")

        assertEquals(
            null,
            StartOverResolver.liveRewindMs(30 * 60_000L, now.minusSeconds(3_600), now),
        )
    }

    @Test fun displayDurationsAndSeekStepCycleIndependently() {
        assertEquals(8, DisplaySettingOptions.cycleChannelInfoSeconds(5, 1))
        assertEquals(8, DisplaySettingOptions.cycleChannelInfoSeconds(3, -1))
        assertEquals(15, DisplaySettingOptions.cycleSeekOverlaySeconds(10, 1))
        assertEquals(10, DisplaySettingOptions.cycleSeekStepSeconds(60, 1))
    }

    @Test fun invalidDisplaySettingsUseSafeDefaults() {
        assertEquals(5, DisplaySettingOptions.validChannelInfoSeconds(99))
        assertEquals(10, DisplaySettingOptions.validSeekOverlaySeconds(99))
        assertEquals(10, DisplaySettingOptions.validSeekStepSeconds(99))
    }
}
