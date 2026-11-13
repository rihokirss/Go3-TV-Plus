package ee.local.go3tvplus.catalog

import android.os.SystemClock
import android.view.KeyEvent
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ee.local.go3tvplus.MainActivity
import ee.local.go3tvplus.TvApplication
import ee.local.go3tvplus.ui.TvViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.request.SuccessResult
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

/** Real remote focus transitions; browsing only, with no VOD playback/history writes. */
@RunWith(AndroidJUnit4::class)
class CatalogFocusDeviceTest {
    @get:Rule val activity = createAndroidComposeRule<MainActivity>()
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrument.runOnMainSync(block)
    private fun key(code: Int) {
        instrument.sendKeyDownUpSync(code)
        activity.waitForIdle()
    }
    private fun waitFor(label: String, condition: () -> Boolean) {
        try {
            activity.waitUntil(60_000) {
                var ready = false
                main { ready = condition() }
                ready
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Timed out: $label", e)
        }
    }
    private fun withMovies(test: (TvViewModel, CatalogViewModel) -> Unit) {
        lateinit var tv: TvViewModel
        activity.activityRule.scenario.onActivity { tv = ViewModelProvider(it)[TvViewModel::class.java] }
        waitFor("signed-in profile") { tv.catalog.state.value.profile != null }
        val model = tv.catalog
        val app = (instrument.targetContext.applicationContext as TvApplication).container
        val count = runBlocking { app.catalog.dao.revisions(model.state.value.profile!!).first().size }
        waitFor("cached titles") { model.state.value.titles.size >= count.coerceAtLeast(1) }
        main {
            model.select(CatalogSection.MOVIES)
            // Start on a long, deterministic row; a short Continue row may end
            // at See all before this test's two RIGHT presses.
            val rows = model.state.value.rows()
            val recent = rows.first { it.key == "recent" }
            model.focusedKeys["MOVIES"] = "recent:${recent.items.first().id}"
            model.scrollPositions["MOVIES:rows"] = rows.indexOf(recent) to 0
            model.scrollPositions["MOVIES:recent"] = 0 to 0
        }
        waitFor("initial movie poster") { !model.state.value.menu && !model.state.value.contentFocusPending && model.focusedKeys["MOVIES"]?.let { !it.startsWith("filter:") } == true }
        test(tv, model)
    }
    private fun assertFocusRemains(model: CatalogViewModel, page: String, target: String, phase: String = "page transition") {
        repeat(15) {
            activity.waitForIdle()
            main {
                assertFalse("Focus returned to the rail: $phase, sample $it", model.state.value.menu)
                assertEquals("Focus briefly moved to another control: $phase, sample $it", target, model.focusedKeys[page])
            }
            SystemClock.sleep(20)
        }
    }
    private fun waitForRailFocus() {
        activity.waitUntil(5000) {
            runCatching { activity.onNodeWithTag("catalog-menu:MOVIES").assertIsFocused(); true }.getOrDefault(false)
        }
    }

    @Test fun rightFromMenuRestoresPosterOrFilterWithoutAnIntermediateControl() = withMovies { _, model ->
        val page = model.state.value.page!!.key
        repeat(2) {
            val before = model.focusedKeys[page]
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitFor("poster moved RIGHT") { model.focusedKeys[page] != before }
        }
        val poster = model.focusedKeys.getValue(page)
        assertFalse(poster.startsWith("filter:"))
        assertFocusRemains(model, page, poster, "before opening the menu")
        repeat(8) {
            key(KeyEvent.KEYCODE_BACK)
            waitFor("menu opened") { model.state.value.menu }
            waitForRailFocus()
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitFor("RIGHT delivered to the menu") {
                assertEquals("RIGHT must not visit an intermediate control", poster, model.focusedKeys[page])
                !model.state.value.menu
            }
            assertFocusRemains(model, page, poster, "menu RIGHT iteration $it")
        }
        // History can add a Continue row above the remembered card while browsing.
        // Reach the filters through the actual rows, rather than assuming one UP.
        repeat(12) {
            if (model.focusedKeys[page]?.startsWith("filter:") != true) {
                val before = model.focusedKeys[page]
                key(KeyEvent.KEYCODE_DPAD_UP)
                waitFor("UP moves toward filters") { model.focusedKeys[page] != before }
            }
        }
        waitFor("filter focus") { model.focusedKeys[page]?.startsWith("filter:") == true }
        repeat(5) {
            if (!model.state.value.menu && model.focusedKeys[page] != "filter:rights") {
                val before = model.focusedKeys[page]
                key(KeyEvent.KEYCODE_DPAD_LEFT)
                waitFor("filter moved LEFT") { model.state.value.menu || model.focusedKeys[page] != before }
            }
        }
        assertEquals("filter:rights", model.focusedKeys[page])
        if (!model.state.value.menu) key(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor("first filter LEFT opens menu") { model.state.value.menu }
        waitForRailFocus()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("RIGHT restores the filter") {
            assertEquals("filter:rights", model.focusedKeys[page])
            !model.state.value.menu
        }
        assertFocusRemains(model, page, "filter:rights")
    }

    @Test fun seeAllFocusesTheGridAndBackRestoresTheSameRowButton() = withMovies { tv, model ->
        val player = tv.mediaPlayer
        val channel = tv.state.value.currentChannelId
        val page = model.state.value.page!!.key
        repeat(32) {
            if (model.focusedKeys[page]?.endsWith(":all") != true) {
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                SystemClock.sleep(60)
            }
        }
        val all = model.focusedKeys.getValue(page)
        assertTrue("Reach the actual See all button", all.endsWith(":all"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("grid owns focus") {
            model.state.value.page?.grid == true && !model.state.value.menu && !model.state.value.contentFocusPending &&
                model.focusedKeys[model.state.value.page!!.key]?.startsWith("grid:") == true
        }
        val gridPage = model.state.value.page!!.key
        assertFocusRemains(model, gridPage, model.focusedKeys.getValue(gridPage))
        key(KeyEvent.KEYCODE_BACK)
        waitFor("same See all button after BACK") { model.state.value.page?.key == page && !model.state.value.menu && model.focusedKeys[page] == all && !model.state.value.contentFocusPending }
        assertFocusRemains(model, page, all)
        assertSame(player, tv.mediaPlayer)
        assertEquals(channel, tv.state.value.currentChannelId)
    }

    @Test fun detailBackRestoresTheSamePosterAndScrollInRowAndGrid() = withMovies { tv, model ->
        val player = tv.mediaPlayer
        val channel = tv.state.value.currentChannelId
        val page = model.state.value.page!!.key
        repeat(12) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        val selected = model.focusedKeys.getValue(page)
        assertTrue(selected.startsWith("recent:"))
        assertFalse(selected.endsWith(":all"))
        fun roundTrip(pageKey: String, target: String, positionKey: String) {
            activity.onNodeWithTag("catalog-poster:$pageKey:$target").assertIsFocused()
            val beforeItems = if (model.state.value.page!!.grid) model.state.value.browseItems() else model.state.value.rows().first { it.key == "recent" }.items
            val beforeIndex = beforeItems.indexOfFirst { target.endsWith(":${it.id}") }
            val beforeBounds = activity.onNodeWithTag("catalog-poster:$pageKey:$target").fetchSemanticsNode().boundsInRoot
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("detail owns focus") {
                model.state.value.page?.detailId != null && !model.state.value.contentFocusPending && !model.state.value.menu
            }
            val position = model.scrollPositions[positionKey]
            assertNotNull(position)
            assertTrue("Test must scroll beyond the initial viewport", position!!.first > 0)
            key(KeyEvent.KEYCODE_BACK)
            waitFor("same poster after detail BACK") {
                model.state.value.page?.key == pageKey && !model.state.value.contentFocusPending && model.focusedKeys[pageKey] == target
            }
            assertFocusRemains(model, pageKey, target, "detail BACK")
            activity.onNodeWithTag("catalog-poster:$pageKey:$target").assertIsFocused()
            val afterItems = if (model.state.value.page!!.grid) model.state.value.browseItems() else model.state.value.rows().first { it.key == "recent" }.items
            val afterIndex = afterItems.indexOfFirst { target.endsWith(":${it.id}") }
            val afterBounds = activity.onNodeWithTag("catalog-poster:$pageKey:$target").fetchSemanticsNode().boundsInRoot
            assertEquals("Restore the exact viewport, item index $beforeIndex -> $afterIndex, bounds $beforeBounds -> $afterBounds", position, model.scrollPositions[positionKey])
        }
        roundTrip(page, selected, "$page:recent")
        repeat(25) { if (model.focusedKeys[page]?.endsWith(":all") != true) key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertTrue(model.focusedKeys[page]!!.endsWith(":all"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("grid") { model.state.value.page?.grid == true && !model.state.value.contentFocusPending }
        val grid = model.state.value.page!!.key
        repeat(4) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(2) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        val gridPoster = model.focusedKeys.getValue(grid)
        assertTrue(gridPoster.startsWith("grid:"))
        roundTrip(grid, gridPoster, "$grid:grid")
        assertSame(player, tv.mediaPlayer)
        assertEquals(channel, tv.state.value.currentChannelId)
    }

    @Test fun posterReusesMemoryAndDiskWithoutAnotherNetworkRequest() = withMovies { _, model ->
        val context = instrument.targetContext
        val density = context.resources.displayMetrics.density
        val title = model.state.value.browseItems().first { it.poster != null }
        val request = catalogPosterRequest(context, title.poster, (116 * density).roundToInt(), (157 * density).roundToInt())
        val loader = SingletonImageLoader.get(context)
        val first = runBlocking { loader.execute(request) }
        assertTrue("Poster must decode successfully", first is SuccessResult)
        val cached = runBlocking { loader.execute(request) }
        assertTrue(cached is SuccessResult)
        assertEquals(DataSource.MEMORY_CACHE, (cached as SuccessResult).dataSource)
        loader.memoryCache!!.remove((first as SuccessResult).memoryCacheKey!!)
        val disk = runBlocking { loader.execute(request) }
        assertTrue(disk is SuccessResult)
        assertEquals(DataSource.DISK, (disk as SuccessResult).dataSource)
    }
}
