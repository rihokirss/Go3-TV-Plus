package ee.local.go3tvplus.catalog

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ee.local.go3tvplus.MainActivity
import ee.local.go3tvplus.ui.TvViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a signed-in real TV. It never buys content or writes account history. */
@RunWith(AndroidJUnit4::class)
class CatalogWorkflowDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java).also {
        // Runs before the rule launches the Activity and creates its ViewModel.
        if (InstrumentationRegistry.getArguments().getString("suspendHistorySync") == "true") {
            InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("local_diagnostics", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("suspend_history_sync", true).commit()
        }
    }
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var tv: TvViewModel
    private val model get() = tv.catalog
    private fun main(block: () -> Unit) = instrument.runOnMainSync(block)
    private fun waitFor(label: String, timeout: Long = 60_000, block: () -> Boolean) {
        android.util.Log.i("CatalogAcceptance", "Waiting: $label")
        val until = SystemClock.elapsedRealtime() + timeout
        var loggedAt = 0L
        while (SystemClock.elapsedRealtime() < until) {
            var done = false; main { done = block() }; if (done) { android.util.Log.i("CatalogAcceptance", "Passed: $label"); return }
            if (SystemClock.elapsedRealtime() - loggedAt > 10_000) {
                loggedAt = SystemClock.elapsedRealtime()
                android.util.Log.i("CatalogAcceptance", "Still $label titles=${model.state.value.titles.size} loading=${model.state.value.loading} heapMb=${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576}")
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out: $label; catalogError=${model.state.value.error}; tvError=${tv.state.value.error}; loading=${model.state.value.loading}; titles=${model.state.value.titles.size}")
    }
    private fun key(code: Int) { instrument.sendKeyDownUpSync(code) }
    private fun capture(name: String) {
        val file = java.io.File(instrument.targetContext.cacheDir, "$name.png")
        instrument.uiAutomation.takeScreenshot()?.let { bitmap -> file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }; bitmap.recycle() }
    }
    private fun keyboardShown(): Boolean {
        val command = instrument.uiAutomation.executeShellCommand("dumpsys input_method")
        val dump = android.os.ParcelFileDescriptor.AutoCloseInputStream(command).bufferedReader().use { it.readText() }
        return dump.contains("mInputShown=true")
    }
    private fun waitKeyboard(shown: Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < until) {
            if (keyboardShown() == shown) return
            SystemClock.sleep(200)
        }
        fail("Keyboard visible should be $shown")
    }
    @Test fun searchKeyboardBackAndSettingsReturnPreserveMenuFocus() {
        activity.scenario.onActivity { tv = ViewModelProvider(it)[TvViewModel::class.java] }
        waitFor("search TV ready") { tv.state.value.selectedProfileId != null && tv.mediaPlayer.playbackState == Player.STATE_READY }
        main { model.select(CatalogSection.SEARCH) }
        waitFor("search field focus") { model.focusedKeys[CatalogSection.SEARCH.name] == "search" }
        waitKeyboard(true)
        key(KeyEvent.KEYCODE_BACK); waitKeyboard(false)
        assertTrue(model.state.value.visible)
        assertEquals(CatalogSection.SEARCH.name, model.state.value.page?.key)
        key(KeyEvent.KEYCODE_BACK)
        waitFor("search BACK opens menu") { model.state.value.menu }
        main { model.select(CatalogSection.SETTINGS) }
        waitFor("catalogue settings") { tv.state.value.overlay == ee.local.go3tvplus.ui.Overlay.APP_SETTINGS }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("settings BACK restores search menu") { model.state.value.visible && model.state.value.menu }
        SystemClock.sleep(1200)
        waitKeyboard(false)
        val bounds = android.graphics.Rect()
        val focused = instrument.uiAutomation.rootInActiveWindow?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        assertNotNull(focused)
        focused!!.getBoundsInScreen(bounds)
        assertTrue("Search autofocus stole the menu focus", bounds.left < 360)
        main { model.select(CatalogSection.MOVIES) }
        waitFor("movies after search settings") { model.state.value.page?.key == CatalogSection.MOVIES.name && !model.state.value.menu }
    }
    @Test fun cachedCatalogueRestoresSameCardAfterMenuAndDetailWithoutLosingFocus() {
        activity.scenario.onActivity { tv = ViewModelProvider(it)[TvViewModel::class.java] }
        waitFor("focus test TV ready") { tv.state.value.selectedProfileId != null && tv.mediaPlayer.playbackState == Player.STATE_READY }
        main { model.select(CatalogSection.MOVIES) }
        waitFor("focus test first poster") { model.focusedKeys[CatalogSection.MOVIES.name] != null }
        val channel = tv.state.value.currentChannelId
        val pattern = listOf(22, 22, 21, 21, 20, 22, 21, 19)
        repeat(20) { index ->
            key(pattern[index % pattern.size])
            assertNotNull("Focus lost at $index", instrument.uiAutomation.rootInActiveWindow?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT))
        }
        val previous = model.focusedKeys[CatalogSection.MOVIES.name]
        main { model.openMenu() }; SystemClock.sleep(250)
        val started = System.nanoTime()
        main { model.select(CatalogSection.MOVIES) }
        waitFor("same cached poster after menu") { model.lastFocusAtNanos > started && model.focusedKeys[CatalogSection.MOVIES.name] == previous }
        android.util.Log.i("CatalogAcceptance", "Cached menu return ms=${(model.lastFocusAtNanos - started) / 1_000_000.0}")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("focus test detail") { model.state.value.detail != null }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("focus test same card after BACK") { model.state.value.page?.key == CatalogSection.MOVIES.name && model.focusedKeys[CatalogSection.MOVIES.name] == previous }
        key(KeyEvent.KEYCODE_9); key(KeyEvent.KEYCODE_CHANNEL_UP)
        assertEquals(channel, tv.state.value.currentChannelId)
        main { assertTrue(tv.mediaPlayer.isCurrentMediaItemLive) }
    }
    @Test fun signedInMovieAndMultiseasonSeriesRoundTripWithRemoteFocus() {
        assertTrue("Playback boundary tests require -e suspendHistorySync true; clean their local progress/outbox before production installation",
            instrument.targetContext.getSharedPreferences("local_diagnostics", android.content.Context.MODE_PRIVATE).getBoolean("suspend_history_sync", false))
        activity.scenario.onActivity { tv = ViewModelProvider(it)[TvViewModel::class.java] }
        waitFor("TV ready") { tv.state.value.selectedProfileId != null && tv.mediaPlayer.playbackState == Player.STATE_READY }
        val channel = tv.state.value.currentChannelId
        main { model.select(CatalogSection.MOVIES) }
        waitFor("first usable catalogue page", 60_000) { model.state.value.titles.any { it.kind == CatalogKind.MOVIE && it.right == ViewingRight.AVAILABLE } }
        capture("catalog-movies")
        val movie = model.state.value.titles.firstOrNull { it.id == "3487393" && it.right == ViewingRight.AVAILABLE } ?: model.state.value.titles.first { it.kind == CatalogKind.MOVIE && it.right == ViewingRight.AVAILABLE }
        assertEquals(ViewingRight.AVAILABLE, movie.right)
        val locked = model.state.value.titles.first { it.kind == CatalogKind.MOVIE && it.right == ViewingRight.LOCKED }
        main { model.open(locked) }
        waitFor("locked detail") { !model.state.value.detailLoading }
        main { model.play(locked) }
        assertTrue(model.state.value.visible)
        assertTrue(model.state.value.error!!.contains("paketti"))
        // Simulate rights becoming stale after browsing; Go3 must deny the normal ticket
        // and the single player must restore the previous TV without losing the detail.
        main { model.play(locked.copy(right = ViewingRight.AVAILABLE)) }
        waitFor("server denied stale rights, TV restored") { model.state.value.visible && model.state.value.error != null && tv.mediaPlayer.isCurrentMediaItemLive && tv.mediaPlayer.playbackState == Player.STATE_READY }
        assertEquals(locked.id, model.state.value.detail?.id)
        main { model.back() }
        // Search must be local, accent insensitive, and discard a superseded query.
        main { model.select(CatalogSection.SEARCH); model.query("does-not-exist"); model.query(normalizeCatalogText(movie.title)) }
        waitFor("latest search") { !model.state.value.searching && model.state.value.searchIds?.contains(movie.id) == true }
        main { model.open(movie) }
        waitFor("movie detail") { !model.state.value.detailLoading }
        capture("catalog-detail")
        main { model.play(movie, true) }
        waitFor("movie video") { tv.mediaPlayer.playbackState == Player.STATE_READY && !tv.mediaPlayer.isCurrentMediaItemLive }
        val player = tv.mediaPlayer
        main { player.seekTo(60_000); player.pause() }
        val profile = model.state.value.profile!!
        waitFor("pause progress") { model.state.value.progress[movie.id]?.positionMs?.let { it >= 59_000 } == true }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("TV restored") { model.state.value.visible && model.state.value.detail?.id == movie.id && tv.mediaPlayer.isCurrentMediaItemLive && tv.mediaPlayer.playbackState == Player.STATE_READY }
        assertEquals(channel, tv.state.value.currentChannelId)
        assertSame(player, tv.mediaPlayer)
        main { model.play(movie) }
        waitFor("resume movie") { tv.mediaPlayer.playbackState == Player.STATE_READY && !tv.mediaPlayer.isCurrentMediaItemLive && tv.mediaPlayer.currentPosition >= 59_000 }
        activity.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        SystemClock.sleep(500)
        activity.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        waitFor("same movie after background") { tv.mediaPlayer.playbackState == Player.STATE_READY && !tv.mediaPlayer.isCurrentMediaItemLive && tv.mediaPlayer.currentPosition >= 59_000 }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("detail return") { model.state.value.visible }
        main { model.select(CatalogSection.SERIES) }
        val series = model.state.value.titles.firstOrNull { it.id == "2694532" && it.right == ViewingRight.AVAILABLE }
            ?: model.state.value.titles.first { it.id == "1024556" && it.right == ViewingRight.AVAILABLE }
        main { model.open(series) }
        waitFor("seasons") { !model.state.value.detailLoading && model.state.value.seasons[series.id].orEmpty().size >= 2 }
        val secondSeason = model.state.value.seasons.getValue(series.id)[1]
        main { model.selectSeason(series.id, secondSeason.id) }
        waitFor("second season episodes") { !model.state.value.detailLoading && model.state.value.titles.any { it.seriesId == series.id && it.seasonId == secondSeason.id } }
        val season = model.state.value.seasons.getValue(series.id).first()
        main { model.selectSeason(series.id, season.id) }
        waitFor("episodes") { model.state.value.titles.any { it.seriesId == series.id && it.seasonId == season.id } }
        val episodes = model.state.value.titles.filter { it.seriesId == series.id && it.seasonId == season.id }.sortedBy { it.episode }
        val episode = episodes.first()
        assertEquals(ViewingRight.AVAILABLE, episode.right)
        main { model.play(episode, true) }
        waitFor("episode video") { tv.mediaPlayer.playbackState == Player.STATE_READY && !tv.mediaPlayer.isCurrentMediaItemLive }
        // Turning automatic continuation off must suppress a real end countdown.
        main { if (model.state.value.autoplay) model.autoplay(); player.seekTo((player.duration - 700).coerceAtLeast(0)); player.play() }
        waitFor("autoplay disabled at real end") { player.playbackState == Player.STATE_ENDED }
        main { assertNull(model.state.value.countdown); model.autoplay() }
        // Use the actual player end, rather than calling the countdown handler directly.
        main { player.seekTo((player.duration - 700).coerceAtLeast(0)); player.play() }
        waitFor("actual episode end", 10_000) { model.state.value.countdown != null }
        key(KeyEvent.KEYCODE_BACK)
        main { assertNull(model.state.value.countdown) }
        // Replaying the end must offer the next episode again, and OK must start it.
        main { player.seekTo((player.duration - 700).coerceAtLeast(0)); player.play() }
        waitFor("second countdown", 10_000) { model.state.value.countdown != null }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("next episode") { tv.mediaPlayer.playbackState == Player.STATE_READY && tv.mediaPlayer.mediaMetadata.title?.toString() == episodes[1].title }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("series detail") { model.state.value.visible && model.state.value.detail?.id == series.id }
        capture("catalog-series")
        // Watchlist stays in this profile and is reversible for this device test.
        val listedBefore = series.id in model.state.value.list
        main { model.toggleList(series) }
        val databaseList = runBlocking { kotlinx.coroutines.withTimeoutOrNull(5000) {
            (instrument.targetContext.applicationContext as ee.local.go3tvplus.TvApplication).container.catalog.dao.watchlist(profile).first()
        } }
        android.util.Log.i("CatalogAcceptance", "Watchlist after toggle: dbRead=${databaseList != null}; dbChanged=${databaseList?.any { it.id == series.id } != listedBefore}; uiChanged=${(series.id in model.state.value.list) != listedBefore}")
        waitFor("watchlist") { (series.id in model.state.value.list) != listedBefore }
        main { model.toggleList(series) }
        waitFor("watchlist restored") { (series.id in model.state.value.list) == listedBefore }
        val rowsOpenedAt = System.nanoTime()
        main { model.focusedKeys.remove(CatalogSection.MOVIES.name); model.select(CatalogSection.MOVIES) }
        waitFor("rows focused") { model.lastFocusAtNanos > rowsOpenedAt && model.focusedKeys[CatalogSection.MOVIES.name] != null }
        val pattern = listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
        val focusLatencies = mutableListOf<Double>()
        repeat(100) { index ->
            val beforeFocus = model.lastFocusAtNanos
            val sentAt = System.nanoTime()
            key(pattern[index % pattern.size])
            val focusedAt = model.lastFocusAtNanos
            if (focusedAt > beforeFocus && focusedAt >= sentAt) focusLatencies += (focusedAt - sentAt) / 1_000_000.0
            val focused = instrument.uiAutomation.rootInActiveWindow?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            assertNotNull("Remote focus lost at move $index", focused)
            assertEquals(channel, tv.state.value.currentChannelId)
        }
        android.util.Log.i("CatalogAcceptance", "100 remote moves; focus samples=${focusLatencies.size}; maxMs=${focusLatencies.maxOrNull()}; medianMs=${focusLatencies.sorted().getOrNull(focusLatencies.size / 2)}")
        capture("catalog-after-100-dpad")
        main { model.openMenu() }
        SystemClock.sleep(250)
        val openedAt = System.nanoTime()
        main { model.select(CatalogSection.MOVIES) }
        waitFor("cached poster focus") { model.lastFocusAtNanos > openedAt }
        android.util.Log.i("CatalogAcceptance", "Cached focus ready ms=${(model.lastFocusAtNanos - openedAt) / 1_000_000.0}")
        val originalFocus = model.focusedKeys[CatalogSection.MOVIES.name]
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("remote open detail") { model.state.value.detail != null }
        key(KeyEvent.KEYCODE_BACK)
        waitFor("same poster restored") { model.state.value.page?.key == CatalogSection.MOVIES.name && model.focusedKeys[CatalogSection.MOVIES.name] == originalFocus }

        // Digits and channel keys cannot tune the background TV.
        key(KeyEvent.KEYCODE_9); key(KeyEvent.KEYCODE_CHANNEL_UP)
        assertEquals(channel, tv.state.value.currentChannelId)
        main { model.select(CatalogSection.TV) }
        waitFor("final TV") { !model.state.value.visible && tv.mediaPlayer.isCurrentMediaItemLive && tv.mediaPlayer.playbackState == Player.STATE_READY }
        assertEquals(profile, tv.state.value.selectedProfileId)
        assertSame(player, tv.mediaPlayer)
    }
}
