package ee.local.go3tvplus.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
