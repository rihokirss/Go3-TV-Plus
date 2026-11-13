package ee.local.go3tvplus.catalog

import android.os.SystemClock
import android.view.KeyEvent
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ee.local.go3tvplus.MainActivity
import ee.local.go3tvplus.TvApplication
import ee.local.go3tvplus.data.Go3HttpGateway
import ee.local.go3tvplus.ui.TvViewModel
import ee.local.go3tvplus.ui.Overlay
import ee.local.go3tvplus.ui.AppSetting
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only Go3 browsing acceptance test; requires a signed-in TV. */
@RunWith(AndroidJUnit4::class)
class CatalogGenresAccountDeviceTest {
    @Test fun settingsSubmenuBackAndLeftReturnToMainNavigation(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var tv: TvViewModel
            scenario.onActivity { tv = ViewModelProvider(it)[TvViewModel::class.java] }
            withTimeout(60_000) { tv.state.first { it.selectedProfileId != null } }
            val player = tv.mediaPlayer
            val channel = tv.state.value.currentChannelId
            instrument.runOnMainSync { tv.catalog.select(CatalogSection.SETTINGS) }
            assertEquals(Overlay.APP_SETTINGS, tv.state.value.overlay)
            repeat(AppSetting.DISPLAY.ordinal) { instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN) }
            instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(Overlay.DISPLAY_SETTINGS, tv.state.value.overlay)
            instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            assertEquals(Overlay.APP_SETTINGS, tv.state.value.overlay)
            assertEquals(AppSetting.DISPLAY.ordinal, tv.state.value.appSettingsIndex)
            instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            assertEquals(Overlay.NONE, tv.state.value.overlay)
            assertTrue(tv.catalog.state.value.visible && tv.catalog.state.value.menu)
            assertEquals(CatalogSection.SETTINGS, tv.catalog.state.value.section)
            assertEquals(channel, tv.state.value.currentChannelId)
            assertSame(player, tv.mediaPlayer)
        }
    }

    @Test fun realGo3WeeklyRankingReturnsOrderedMovies(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as TvApplication).container
        val profile = app.preferences.selectedProfile() ?: error("Signed-in profile required")
        val ranking = Go3HttpGateway(context).catalogPopularMovies(app.auth.accessToken(), profile)
        android.util.Log.i("CatalogAcceptance", "Live weekly Go3 ranking movies=${ranking.size}")
        assertTrue("Go3 weekly film ranking must be available on the signed-in TV", ranking.isNotEmpty())
        assertTrue(ranking.all { it.text("type") == "VOD" })
        assertEquals(ranking.size, ranking.map { it.text("id") }.distinct().size)
    }
    @Test fun realGenresAndGo3PopularityWorkBeforeOpeningAnyTitleAndKeepRemoteFocus(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val app = (context.applicationContext as TvApplication).container
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var tv: TvViewModel
            scenario.onActivity { tv = ViewModelProvider(it)[TvViewModel::class.java] }
            withTimeout(60_000) {
                var ready = false
                while (!ready) {
                    instrument.runOnMainSync { ready = tv.state.value.selectedProfileId != null && tv.mediaPlayer.playbackState == Player.STATE_READY }
                    if (!ready) delay(200)
                }
            }
            val model = tv.catalog
            val player = tv.mediaPlayer
            val profile = tv.state.value.selectedProfileId!!
            val channel = tv.state.value.currentChannelId
            val cachedCount = app.catalog.dao.revisions(profile).first().size
            instrument.runOnMainSync { model.select(CatalogSection.MOVIES) }
            withTimeout(180_000) { model.state.first { !it.loading && it.error == null && it.titles.size >= cachedCount.coerceAtLeast(1) && it.popularMovieIds.isNotEmpty() && app.catalog.dao.syncSnapshot(profile)?.genresCheckedAt?.let { time -> time > 0 } == true } }
            assertFalse(app.catalog.needsRefresh(profile))
            val state = model.state.value
            val rows = state.rows()
            val genres = rows.filter { it.key.startsWith("genre:") }
            assertTrue("Expected multiple populated genres before opening details", genres.count { it.items.size >= 5 } >= 5)
            val popular = rows.single { it.key == "popular" }
            assertTrue(popular.items.isNotEmpty())
            assertEquals(state.popularMovieIds.filter { id -> state.filtered().any { it.id == id && it.kind == CatalogKind.MOVIE } }, popular.items.map { it.id })
            val sample = genres.maxBy { it.items.size }
            instrument.runOnMainSync { model.filter(state.page!!.filter.copy(genre = sample.title)) }
            assertTrue(model.state.value.filtered().all { sample.title in it.genres })
            instrument.runOnMainSync { model.all(model.state.value.rows().first { it.key == "recent" }) }
            assertEquals(sample.title, model.state.value.page!!.filter.genre)
            instrument.runOnMainSync { model.back(); model.filter(state.page!!.filter); model.all(popular) }
            assertEquals("popular", model.state.value.page!!.group)
            assertEquals(popular.items.map { it.id }, model.state.value.popularMovies().map { it.id })
            instrument.runOnMainSync { model.back() }
            // Let asynchronous row computation and focus restoration finish after
            // the programmatic filter/grid transitions before sending real keys.
            instrument.waitForIdleSync()
            withTimeout(5000) {
                while (instrument.uiAutomation.rootInActiveWindow?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT) == null) delay(100)
            }
            val keys = listOf(22, 22, 21, 21, 20, 22, 21, 19)
            repeat(104) { index ->
                instrument.uiAutomation.injectInputEvent(KeyEvent(KeyEvent.ACTION_DOWN, keys[index % keys.size]), true)
                instrument.uiAutomation.injectInputEvent(KeyEvent(KeyEvent.ACTION_UP, keys[index % keys.size]), true)
                SystemClock.sleep(160)
                assertNotNull("Remote focus lost at $index", instrument.uiAutomation.rootInActiveWindow?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT))
            }
            assertSame(player, tv.mediaPlayer)
            assertEquals(channel, tv.state.value.currentChannelId)
            instrument.runOnMainSync { assertTrue(tv.mediaPlayer.isCurrentMediaItemLive) }
            android.util.Log.i("CatalogAcceptance", "Genre rows=${genres.size}; largest=${sample.items.size}; popular=${popular.items.size}; 104 D-pad events retained focus")
        }
    }
}
