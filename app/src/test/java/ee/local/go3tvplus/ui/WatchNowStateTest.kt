package ee.local.go3tvplus.ui

import ee.local.go3tvplus.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WatchNowStateTest {
    private val now = Instant.parse("2026-11-10T20:00:00Z")
    private val future = Program("future", "tv3", "Film", null, now.plusSeconds(86400), now.plusSeconds(90000), true)
    private val other = future.copy(id = "other", title = "Selected programme")
    private val movie = CatalogMovie("movie", "epg", "Film", null, 6000, now.minusSeconds(100), now.plusSeconds(200))
    private val available = WatchNowState(future, sources = listOf(WatchNowSource.Movie(movie)), checkedAt = now)
    private val state = TvUiState(
        selectedProfileId = "profile", channels = listOf(Channel("tv3", "TV3")),
        programsByChannel = mapOf("tv3" to listOf(other, future)), overlay = Overlay.GUIDE,
        watchNowProfileId = "profile", watchNowChannelIds = setOf("tv3"), watchNowResults = mapOf(future.id to available),
    )

    @Test fun unselectedVisibleProgrammeIsMarkedAndSelectionCanUseItsResultImmediately() {
        assertEquals(other, state.selectedScheduleProgram)
        assertTrue(state.watchNowFor(future, now).availableFor(future, now))
        assertTrue(state.copy(guideProgramIndex = 1).watchNowFor(future, now).availableFor(future, now))
    }

    @Test fun changingProfileOrPermissionsNeverShowsAPreviousContextsMarker() {
        assertFalse(state.copy(selectedProfileId = "kids").watchNowFor(future, now).availableFor(future, now))
        assertFalse(state.copy(channels = emptyList()).watchNowFor(future, now).availableFor(future, now))
        assertFalse(state.watchNowFor(future.copy(secondaryId = "another-episode"), now).availableFor(future, now))
    }

    @Test fun expiredContentLosesItsMarkerButRefreshAgeAloneDoesNotHideValidContent() {
        assertFalse(available.availableFor(future, now.plusSeconds(201)))
        val old = available.copy(checkedAt = now.minusSeconds(301))
        assertTrue(old.availableFor(future, now))
        assertFalse(old.isFresh(now))
        assertTrue(state.copy(watchNowResults = mapOf(future.id to old)).watchNowFor(future, now).checking)
        assertFalse(available.availableFor(future.copy(startsAt = now), now))
    }

    @Test fun descriptionAndFutureCatchupUpdatesDoNotBlinkAnExistingMarker() {
        val refreshed = future.copy(description = "Updated synopsis", catchupAvailable = false)
        assertTrue(state.watchNowFor(refreshed, now).availableFor(refreshed, now))
        assertFalse(state.watchNowFor(future.copy(title = "Different film"), now).availableFor(future, now))
    }

    @Test fun refreshFailurePreservesAValidMatchAndConfirmedAbsenceRemovesIt() {
        val failure = available.refreshed(future, emptyList(), now.plusSeconds(10), failed = true)
        assertTrue(failure.availableFor(future, now.plusSeconds(10)))
        assertEquals(now, failure.checkedAt)
        assertFalse(failure.refreshed(future, emptyList(), now.plusSeconds(20), failed = false).availableFor(future, now.plusSeconds(20)))
        assertFalse(available.refreshed(future, emptyList(), now.plusSeconds(201), failed = true).availableFor(future, now.plusSeconds(201)))
    }
}
