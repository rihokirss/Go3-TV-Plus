package ee.local.go3tvplus.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WatchNowResolverTest {
    private val now = Instant.parse("2026-11-10T20:00:00Z")
    private val future = Program(
        "future-recording", "tv6", "John Wick 3: Parabellum", "USA 2019", now.plusSeconds(86400), now.plusSeconds(93600), true,
        secondaryId = "80050706", epgProvider = "T24", originalTitle = "John Wick: Chapter 3 - Parabellum", catchupUntil = now.plusSeconds(604800),
    )
    private val earlier = future.copy(id = "past-recording", channelId = "tv3", startsAt = now.minusSeconds(8000), endsAt = now.minusSeconds(800))
    private val movie = CatalogMovie("1212295", "80050706", future.title, future.originalTitle, 7521, now.minusSeconds(100), now.plusSeconds(100))
    private val channels = setOf("tv3", "tv6")

    @Test fun earlierEpisodeOnAnotherAvailableChannelIsMatchedAndNewestComesFirst() {
        val old = earlier.copy(id = "older", startsAt = now.minusSeconds(90000), endsAt = now.minusSeconds(82800))
        assertEquals(listOf(earlier, old), WatchNowResolver.recordings(future, listOf(old, earlier, earlier), channels, now).map { it.program })
    }

    @Test fun sameSeriesTitleNeverMatchesAnotherEpisodeOrProvider() {
        assertTrue(WatchNowResolver.recordings(future, listOf(
            earlier.copy(secondaryId = "another-episode"), earlier.copy(epgProvider = "another-provider"),
            earlier.copy(title = "Another programme"), earlier.copy(id = future.id),
        ), channels, now).isEmpty())
    }

    @Test fun unavailableExpiredAndUnfinishedRecordingsAreExcluded() {
        assertTrue(WatchNowResolver.recordings(future, listOf(
            earlier.copy(channelId = "hidden"), earlier.copy(catchupAvailable = false),
            earlier.copy(catchupUntil = now), earlier.copy(catchupUntil = null),
            earlier.copy(endsAt = now.plusSeconds(1)),
        ), channels, now).isEmpty())
    }

    @Test fun recordingEndingNowCanBeWatched() {
        assertEquals(1, WatchNowResolver.recordings(future, listOf(earlier.copy(endsAt = now)), channels, now).size)
    }

    @Test fun missingAndPlaceholderIdentitiesNeverMatch() {
        for (identity in listOf(null, "", " ", "0", "null")) {
            val requested = future.copy(secondaryId = identity)
            assertTrue(WatchNowResolver.recordings(requested, listOf(earlier.copy(secondaryId = identity)), channels, now).isEmpty())
            assertTrue(WatchNowResolver.movies(requested, listOf(movie.copy(externalId = identity.orEmpty())), now).isEmpty())
        }
    }

    @Test fun exactCatalogIdentityAndTitleMatchAMovieBeforeItsBroadcast() {
        assertEquals(listOf(movie), WatchNowResolver.movies(future, listOf(movie), now).map { it.movie })
        assertEquals(1, WatchNowResolver.movies(future.copy(title = "Translated title"), listOf(movie), now).size)
    }

    @Test fun sameNameRemakesUnreleasedAndExpiredMoviesAreExcluded() {
        assertTrue(WatchNowResolver.movies(future, listOf(
            movie.copy(externalId = "other-remake"), movie.copy(title = "Other", originalTitle = "Other"),
            movie.copy(availableFrom = now.plusSeconds(1)), movie.copy(availableUntil = now), movie.copy(durationSeconds = 0),
        ), now).isEmpty())
        assertTrue(WatchNowResolver.movies(future.copy(epgProvider = "unknown"), listOf(movie), now).isEmpty())
    }

    @Test fun punctuationIsToleratedButChapterNumbersArePreserved() {
        assertEquals(1, WatchNowResolver.movies(future, listOf(movie.copy(title = "JOHN WICK 3 - PARABELLUM", originalTitle = null)), now).size)
        assertTrue(WatchNowResolver.movies(future, listOf(movie.copy(title = "John Wick 2: Parabellum", originalTitle = null)), now).isEmpty())
        assertTrue(WatchNowResolver.movies(future.copy(title = "...", originalTitle = null), listOf(movie.copy(title = "---", originalTitle = null)), now).isEmpty())
    }
}
