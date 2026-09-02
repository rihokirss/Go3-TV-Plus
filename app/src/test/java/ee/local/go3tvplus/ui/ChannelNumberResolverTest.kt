package ee.local.go3tvplus.ui

import ee.local.go3tvplus.domain.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelNumberResolverTest {
    private val channels = listOf(
        Channel("a", "Esimene", serverNumber = 4),
        Channel("b", "Teine", serverNumber = 2),
        Channel("c", "Kolmas", serverNumber = 12),
    )

    @Test fun resolvesChannelsByNumber() {
        assertEquals("a", ChannelNumberResolver.resolve(channels, 4)?.id)
        assertEquals("b", ChannelNumberResolver.resolve(channels, 2)?.id)
        assertNull(ChannelNumberResolver.resolve(channels, 3))
    }

    @Test fun rejectsUnknownAndOutOfRangeNumbers() {
        assertNull(ChannelNumberResolver.resolve(channels, 0))
        assertNull(ChannelNumberResolver.resolve(channels, 999))
        assertNull(ChannelNumberResolver.resolve(channels, null))
    }

    @Test fun insertsBeforeOccupiedNumberAndShiftsFollowingChannelsUp() {
        val existing = mapOf("a" to 1, "b" to 2, "c" to 3, "d" to 4)

        assertEquals(
            mapOf("a" to 1, "b" to 3, "c" to 4, "d" to 2),
            ChannelNumberResolver.assignWithShift(existing, "d", 2),
        )
    }

    @Test fun insertsAfterOccupiedNumberAndShiftsPreviousChannelsDown() {
        val existing = mapOf("a" to 1, "b" to 2, "c" to 3, "d" to 4)

        assertEquals(
            mapOf("a" to 3, "b" to 1, "c" to 2, "d" to 4),
            ChannelNumberResolver.assignWithShift(existing, "a", 3),
        )
    }

    @Test fun movesDirectlyWhenTargetNumberIsFree() {
        val existing = mapOf("a" to 1, "b" to 2, "c" to 3)

        assertEquals(
            mapOf("a" to 1, "b" to 8, "c" to 3),
            ChannelNumberResolver.assignWithShift(existing, "b", 8),
        )
    }

    @Test fun rejectsShiftOutsideAllowedRange() {
        val existing = mapOf("a" to 1, "b" to 2)
        assertNull(ChannelNumberResolver.assignWithShift(existing, "a", 0))
        assertNull(ChannelNumberResolver.assignWithShift(existing, "a", 1_000))
    }
}
