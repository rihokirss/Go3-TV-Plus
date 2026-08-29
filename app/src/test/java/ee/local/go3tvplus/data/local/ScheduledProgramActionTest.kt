package ee.local.go3tvplus.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduledProgramActionTest {
    @Test fun roundTripsPersistedAction() {
        val action = ScheduledProgramAction("programme-1", "channel-2", 1_788_000_000_000L, reminder = true, autoTune = true)

        assertEquals(action, decodeScheduledProgramAction(encodeScheduledProgramAction(action)))
    }

    @Test fun rejectsMalformedAndEmptyActions() {
        assertNull(decodeScheduledProgramAction("broken"))
        assertNull(decodeScheduledProgramAction("p|c|1000|0|0"))
    }
}
