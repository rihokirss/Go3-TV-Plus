package ee.local.go3tvplus.catalog

import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ee.local.go3tvplus.MainActivity
import ee.local.go3tvplus.TvApplication
import ee.local.go3tvplus.ui.TvViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Signed-in TV: actual editable field updates results without submitting or starting VOD. */
@RunWith(AndroidJUnit4::class)
class CatalogSearchDeviceTest {
    @get:Rule val activity = createAndroidComposeRule<MainActivity>()
    private val instrument get() = InstrumentationRegistry.getInstrumentation()

    @Test fun typingUpdatesResultsWithoutSubmittingAndLatestEditWins() {
        lateinit var tv: TvViewModel
        activity.activityRule.scenario.onActivity { tv = ViewModelProvider(it)[TvViewModel::class.java] }
        val model = tv.catalog
        activity.waitUntil(60_000) { model.state.value.profile != null }
        val repository = (instrument.targetContext.applicationContext as TvApplication).container.catalog
        val profile = model.state.value.profile!!
        val count = runBlocking { repository.dao.revisions(profile).first().size }
        activity.waitUntil(60_000) { model.state.value.titles.size >= count.coerceAtLeast(1) }
        val movie = model.state.value.titles.first { it.kind == CatalogKind.MOVIE && it.right == ViewingRight.AVAILABLE && normalizeCatalogText(it.title).length >= 3 }
        val prefix = normalizeCatalogText(movie.title).take(3)
        val times = List(12) {
            val start = SystemClock.elapsedRealtimeNanos()
            val ids = runBlocking { repository.search(profile, prefix) }
            assertTrue(movie.id in ids.orEmpty())
            (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        }.sorted()
        instrument.sendStatus(0, Bundle().apply { putString("stream", "\nLocal FTS median=${times[times.size / 2]} ms, max=${times.last()} ms\n") })
        assertTrue("Local search must stay interactive", times[times.size / 2] < 100)
        for (query in listOf(prefix.take(1), normalizeCatalogText(movie.title), "no-match-6bc80c91")) {
            val start = SystemClock.elapsedRealtimeNanos()
            val ids = runBlocking { repository.search(profile, query) }
            val queryMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            val snapshot = model.state.value.copy(section = CatalogSection.SEARCH, searchIds = ids)
            val filterAt = SystemClock.elapsedRealtimeNanos()
            val results = snapshot.browseItems()
            val filterMs = (SystemClock.elapsedRealtimeNanos() - filterAt) / 1_000_000.0
            instrument.sendStatus(0, Bundle().apply { putString("stream", "Search length=${query.length}, results=${results.size}, FTS=${queryMs} ms, filter/sort=${filterMs} ms\n") })
        }
        val serverAt = SystemClock.elapsedRealtimeNanos()
        val updates = runBlocking { repository.searchResults(profile, normalizeCatalogText(movie.title)).toList() }
        val elapsed = (SystemClock.elapsedRealtimeNanos() - serverAt) / 1_000_000.0
        assertTrue("Real Go3 server search must complete", updates.any { !it.searching && !it.offline })
        assertTrue(movie.id in updates.last().items.map { it.id })
        instrument.sendStatus(0, Bundle().apply { putString("stream", "Signed-in server plus cached results=${elapsed} ms\n") })

        activity.runOnIdle { model.select(CatalogSection.SEARCH) }
        activity.waitUntil(10_000) { model.focusedKeys["SEARCH"] == "search" && !model.state.value.contentFocusPending }
        val field = activity.onNodeWithTag("catalog-search-input")
        for (index in prefix.indices) {
            field.performTextInput(prefix[index].toString())
            activity.waitUntil(10_000) { model.state.value.query == prefix.take(index + 1) && !model.state.value.searching }
            assertTrue(movie.id in model.state.value.searchIds.orEmpty())
            field.assertIsFocused() // No IME Search/Done action was used.
            assertVisibleCount(model)
        }
        field.performTextReplacement("no-match-6bc80c91")
        activity.waitUntil(10_000) { !model.state.value.searching && model.state.value.searchIds?.isEmpty() == true }
        assertVisibleCount(model)
        activity.runOnIdle {
            model.query(prefix)
            model.query("no-match-6bc80c91")
            model.query(normalizeCatalogText(movie.title))
        }
        activity.waitUntil(10_000) { !model.state.value.searching && movie.id in model.state.value.searchIds.orEmpty() }
        assertEquals(normalizeCatalogText(movie.title), model.state.value.query)
        assertVisibleCount(model)
        field.performTextClearance()
        activity.runOnIdle {
            assertEquals("", model.state.value.query)
            assertFalse(model.state.value.searching)
            assertNull(model.state.value.searchIds)
        }
        assertVisibleCount(model)
    }

    private fun assertVisibleCount(model: CatalogViewModel) {
        val size = model.state.value.browseItems().size
        val text = if (size == 1) "1 tulemus" else "$size tulemust"
        activity.waitUntil(10_000) {
            runCatching { activity.onNodeWithTag("catalog-search-count").assertTextEquals(text); true }.getOrDefault(false)
        }
    }
}
