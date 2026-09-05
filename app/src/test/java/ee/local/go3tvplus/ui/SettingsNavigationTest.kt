package ee.local.go3tvplus.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsNavigationTest {
    @Test fun nestedLocationsReturnOneLevelWithoutLooping() {
        val origin = Overlay.LOCATIONS_SETTINGS
        val sea = SettingsNavigation.parent(Overlay.SEA_STATION_PICKER, origin)
        val locations = SettingsNavigation.parent(sea, origin)
        val main = SettingsNavigation.parent(locations, origin)
        assertEquals(Overlay.SEA_SETTINGS, sea)
        assertEquals(Overlay.LOCATIONS_SETTINGS, locations)
        assertEquals(Overlay.APP_SETTINGS, main)
        assertEquals(Overlay.NONE, SettingsNavigation.parent(main, origin))
    }

    @Test fun searchesReturnToLocationsAndLanguageReturnsToMain() {
        assertEquals(Overlay.LOCATIONS_SETTINGS, SettingsNavigation.parent(Overlay.WEATHER_LOCATION, Overlay.LOCATIONS_SETTINGS))
        assertEquals(Overlay.LOCATIONS_SETTINGS, SettingsNavigation.parent(Overlay.TRANSIT_STOP_SETTINGS, Overlay.LOCATIONS_SETTINGS))
        assertEquals(Overlay.APP_SETTINGS, SettingsNavigation.parent(Overlay.LANGUAGE_SETTINGS, Overlay.APP_SETTINGS))
        assertEquals(Overlay.NONE, SettingsNavigation.parent(Overlay.WEATHER, Overlay.NONE))
    }

    @Test fun subtitleOffCyclesBothWaysAndAudioAutoWraps() {
        val subtitles = SUBTITLE_LANGUAGE_OPTIONS.map { it.first }
        assertEquals("et", cycleOption(subtitles, null, 1))
        assertEquals("ru", cycleOption(subtitles, null, -1))
        assertEquals(null, cycleOption(subtitles, "et", -1))
        assertEquals("et", cycleOption(AUDIO_LANGUAGE_OPTIONS.map { it.first }, "auto", 1))
    }
}
