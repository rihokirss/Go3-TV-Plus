package ee.local.go3tvplus.ui

import ee.local.go3tvplus.domain.Channel
import ee.local.go3tvplus.domain.DEFAULT_MURASTE_STOP
import ee.local.go3tvplus.domain.DEFAULT_WEATHER_LOCATION
import ee.local.go3tvplus.domain.DeviceAuthState
import ee.local.go3tvplus.domain.Profile
import ee.local.go3tvplus.domain.Program
import ee.local.go3tvplus.domain.SeaForecast
import ee.local.go3tvplus.domain.TransitBoard
import ee.local.go3tvplus.domain.TransitStopSelection
import ee.local.go3tvplus.domain.WeatherForecast
import ee.local.go3tvplus.domain.WeatherLocation
import java.time.Instant

/**
 * [returnsToParent] — BACK sellelt overlay'lt naaseb [TvUiState.settingsReturnOverlay]
 * ekraanile; muidu sulgub overlay otse pildile (NONE).
 */
enum class Overlay(val returnsToParent: Boolean = false) {
    NONE, CHANNEL_RAIL, GUIDE, APP_SETTINGS,
    CHANNEL_SETTINGS(true), PROFILE_SETTINGS(true), AUDIO_SETTINGS(true),
    SUBTITLE_SETTINGS(true), DISPLAY_SETTINGS(true), WEATHER_LOCATION(true),
    WEATHER(true), TRANSIT_STOP_SETTINGS(true), TRANSIT(true), TONIGHT(true), SEEK,
}

/** Seadete pealoendi read; järjekord on ka menüü järjekord. */
enum class AppSetting(val title: String) {
    PROFILE("Go3 profiil"),
    CHANNELS("Kanalid"),
    AUDIO("Helirada"),
    SUBTITLES("Subtiitrid"),
    DISPLAY("Ekraan ja juhtimine"),
    WEATHER("Ilm"),
    TRANSIT("Bussipeatus"),
    REFRESH_PACKAGE("Värskenda kanalipaketti"),
}

/** Ekraaniseaded koos lubatud väärtustega; [options] on tühi lülitite puhul. */
enum class DisplaySetting(
    val title: String,
    val description: String,
    val options: List<Int> = emptyList(),
    val default: Int = 0,
) {
    CLOCK("Kell täisekraanil", "Näita kellaaega video paremas ülanurgas"),
    CHANNEL_INFO("Kanali- ja saateinfo", "Kui kaua kanalipaneel ekraanile jääb", listOf(3, 5, 8), default = 5),
    SEEK_OVERLAY("Kerimisriba kestus", "Kui kaua ajaniheteave ekraanile jääb", listOf(5, 10, 15), default = 10),
    SEEK_STEP("Kerimissamm", "Vasaku ja parema noole hüppe pikkus", listOf(10, 30, 60), default = 10);

    /** Salvestatud väärtus või vaikimisi valik, kui see pole enam lubatud. */
    fun valid(value: Int): Int = value.takeIf(options::contains) ?: default

    fun cycle(current: Int, direction: Int): Int {
        val index = options.indexOf(current).takeIf { it >= 0 } ?: 0
        return options[Math.floorMod(index + if (direction < 0) -1 else 1, options.size)]
    }
}

val AUDIO_LANGUAGE_OPTIONS = listOf(
    "et" to "Eesti",
    "en" to "Inglise",
    "ru" to "Vene",
    "auto" to "Automaatne",
)

val SUBTITLE_LANGUAGE_OPTIONS = listOf(
    null to "Väljas",
    "et" to "Eesti",
    "en" to "Inglise",
    "ru" to "Vene",
)

fun languageLabel(options: List<Pair<String?, String>>, language: String?): String =
    options.firstOrNull { it.first == language }?.second ?: language ?: "Väljas"

/** Üks õhtukava rida: saade koos kanaliga, millel see jookseb. */
data class TonightEntry(val channel: Channel, val program: Program)

data class SearchState<T>(
    val query: String = "",
    val results: List<T> = emptyList(),
    val index: Int = -1,
    val loading: Boolean = false,
    val error: String? = null,
) {
    fun withQuery(query: String) = copy(query = query.take(50), results = emptyList(), index = -1, error = null)
}

data class SeekState(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val liveOffsetMs: Long? = null,
    val isLive: Boolean = false,
    val playing: Boolean = true,
)

/** Ilmapaneeli kaks lehte: tavaline ilm ja kaatrisõiduks mõeldud mereilm. */
enum class WeatherPage { WEATHER, SEA }

data class WeatherState(
    val location: WeatherLocation = DEFAULT_WEATHER_LOCATION,
    val forecast: WeatherForecast? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val search: SearchState<WeatherLocation> = SearchState(),
    val page: WeatherPage = WeatherPage.WEATHER,
    val sea: SeaForecast? = null,
    val seaLoading: Boolean = false,
    val seaError: String? = null,
)

data class TransitState(
    val stop: TransitStopSelection = DEFAULT_MURASTE_STOP,
    val board: TransitBoard? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val directionIndex: Int = 0,
    val departureIndex: Int = 0,
    val search: SearchState<TransitStopSelection> = SearchState(),
)

data class TonightState(
    val entries: List<TonightEntry> = emptyList(),
    val index: Int = 0,
    val now: Instant = Instant.EPOCH,
)

data class TvUiState(
    val auth: DeviceAuthState = DeviceAuthState.Idle,
    val profiles: List<Profile> = emptyList(),
    val selectedProfileId: String? = null,
    val channels: List<Channel> = emptyList(),
    val programsByChannel: Map<String, List<Program>> = emptyMap(),
    val currentChannelId: String? = null,
    val overlay: Overlay = Overlay.NONE,
    val settingsReturnOverlay: Overlay = Overlay.NONE,
    val railIndex: Int = 0,
    /** One shared filter keeps the channel rail and guide in the same mode. */
    val favoritesOnly: Boolean = false,
    val guideChannelIndex: Int = 0,
    val guideProgramIndex: Int = 0,
    val guideAnchor: Instant? = null,
    val guideWindowStart: Instant? = null,
    val appSettingsIndex: Int = 0,
    /** Jagatud kursor kõigile lihtsatele alammenüüdele; korraga on lahti ainult üks. */
    val menuIndex: Int = 0,
    val audioLanguagePreference: String = "et",
    val subtitleLanguagePreference: String? = null,
    val showClock: Boolean = false,
    val channelInfoSeconds: Int = 5,
    val seekOverlaySeconds: Int = 10,
    val seekStepSeconds: Int = 10,
    val weather: WeatherState = WeatherState(),
    val transit: TransitState = TransitState(),
    val tonight: TonightState = TonightState(),
    val favoriteChannelIds: Set<String> = emptySet(),
    val numberInput: String = "",
    /** Set while a catchup stream plays, so overlays show the right programme. */
    val catchupProgram: Program? = null,
    val seek: SeekState = SeekState(),
    val loading: Boolean = false,
    val videoVisible: Boolean = false,
    val error: String? = null,
    val errorActionIndex: Int = 0,
    val scheduledReminderIds: Set<String> = emptySet(),
    val scheduledAutoTuneIds: Set<String> = emptySet(),
    val notice: String? = null,
    val isDemo: Boolean = false,
) {
    /** Kanalid, mida riba ja telekava parajasti näitavad: kas kõik või ainult lemmikud. */
    val visibleChannels: List<Channel>
        get() = if (favoritesOnly) channels.filter { it.id in favoriteChannelIds } else channels

    fun programsFor(channelId: String?): List<Program> =
        if (channelId == null) emptyList() else programsByChannel[channelId].orEmpty()
}
