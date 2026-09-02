package ee.local.go3tvplus.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import ee.local.go3tvplus.domain.DEFAULT_MURASTE_STOP
import ee.local.go3tvplus.domain.DEFAULT_WEATHER_LOCATION
import ee.local.go3tvplus.domain.TransitStopPlatform
import ee.local.go3tvplus.domain.TransitStopSelection
import ee.local.go3tvplus.domain.WeatherLocation

private val Context.tvDataStore by preferencesDataStore("tv_preferences")

data class ChannelPreference(
    val channelId: String,
    val number: Int,
    val favorite: Boolean,
)

data class PlaybackPreferences(
    val audioLanguage: String,
    val subtitleLanguage: String?,
)

data class DisplayPreferences(
    val showClock: Boolean,
    val channelInfoSeconds: Int,
    val seekOverlaySeconds: Int,
    val seekStepSeconds: Int,
)

data class ScheduledProgramAction(
    val programId: String,
    val channelId: String,
    val startsAtEpochMs: Long,
    val reminder: Boolean,
    val autoTune: Boolean,
)

/** Kõik lugemised on ühekordsed hetktõmmised; DataStore hoiab faili ise mälus. */
class TvPreferences(private val context: Context) {
    private object Keys {
        val lastChannel = stringPreferencesKey("last_channel")
        val selectedProfile = stringPreferencesKey("selected_profile")
        val preferredAudio = stringPreferencesKey("preferred_audio")
        val preferredSubtitle = stringPreferencesKey("preferred_subtitle")
        val showClock = booleanPreferencesKey("show_clock")
        val channelInfoSeconds = intPreferencesKey("channel_info_seconds")
        val seekOverlaySeconds = intPreferencesKey("seek_overlay_seconds")
        val seekStepSeconds = intPreferencesKey("seek_step_seconds")
        val weatherLocationName = stringPreferencesKey("weather_location_name")
        val weatherLocationArea = stringPreferencesKey("weather_location_area")
        val weatherLatitude = stringPreferencesKey("weather_latitude")
        val weatherLongitude = stringPreferencesKey("weather_longitude")
        val transitStopName = stringPreferencesKey("transit_stop_name")
        val transitStopPlatforms = stringSetPreferencesKey("transit_stop_platforms")
        val scheduledProgramActions = stringSetPreferencesKey("scheduled_program_actions")
    }

    private suspend fun snapshot(): Preferences = context.tvDataStore.data.first()

    suspend fun lastChannel(): String? = snapshot()[Keys.lastChannel]
    suspend fun saveLastChannel(id: String) = context.tvDataStore.edit { it[Keys.lastChannel] = id }
    suspend fun selectedProfile(): String? = snapshot()[Keys.selectedProfile]
    suspend fun saveSelectedProfile(id: String) = context.tvDataStore.edit { it[Keys.selectedProfile] = id }

    suspend fun playbackPreferences(): PlaybackPreferences = snapshot().let {
        PlaybackPreferences(it[Keys.preferredAudio] ?: "et", it[Keys.preferredSubtitle])
    }
    suspend fun savePreferredAudio(language: String) = context.tvDataStore.edit { it[Keys.preferredAudio] = language }
    suspend fun savePreferredSubtitle(language: String?) = context.tvDataStore.edit {
        if (language == null) it.remove(Keys.preferredSubtitle) else it[Keys.preferredSubtitle] = language
    }

    suspend fun displayPreferences(): DisplayPreferences = snapshot().let {
        DisplayPreferences(
            showClock = it[Keys.showClock] ?: false,
            channelInfoSeconds = it[Keys.channelInfoSeconds] ?: 5,
            seekOverlaySeconds = it[Keys.seekOverlaySeconds] ?: 10,
            seekStepSeconds = it[Keys.seekStepSeconds] ?: 10,
        )
    }
    suspend fun saveShowClock(show: Boolean) = context.tvDataStore.edit { it[Keys.showClock] = show }
    suspend fun saveChannelInfoSeconds(seconds: Int) = context.tvDataStore.edit { it[Keys.channelInfoSeconds] = seconds }
    suspend fun saveSeekOverlaySeconds(seconds: Int) = context.tvDataStore.edit { it[Keys.seekOverlaySeconds] = seconds }
    suspend fun saveSeekStepSeconds(seconds: Int) = context.tvDataStore.edit { it[Keys.seekStepSeconds] = seconds }

    suspend fun weatherLocation(): WeatherLocation {
        val values = snapshot()
        val name = values[Keys.weatherLocationName] ?: return DEFAULT_WEATHER_LOCATION
        val latitude = values[Keys.weatherLatitude]?.toDoubleOrNull() ?: return DEFAULT_WEATHER_LOCATION
        val longitude = values[Keys.weatherLongitude]?.toDoubleOrNull() ?: return DEFAULT_WEATHER_LOCATION
        return WeatherLocation(name, values[Keys.weatherLocationArea], latitude, longitude)
    }

    suspend fun saveWeatherLocation(location: WeatherLocation) = context.tvDataStore.edit {
        it[Keys.weatherLocationName] = location.name
        if (location.area == null) it.remove(Keys.weatherLocationArea) else it[Keys.weatherLocationArea] = location.area
        it[Keys.weatherLatitude] = location.latitude.toString()
        it[Keys.weatherLongitude] = location.longitude.toString()
    }

    suspend fun transitStop(): TransitStopSelection {
        val values = snapshot()
        val name = values[Keys.transitStopName] ?: return DEFAULT_MURASTE_STOP
        val platforms = values[Keys.transitStopPlatforms].orEmpty()
            .mapNotNull(::decodeTransitPlatform)
            .sortedBy(TransitStopPlatform::code)
        return if (platforms.isEmpty()) DEFAULT_MURASTE_STOP else TransitStopSelection(name, platforms)
    }

    suspend fun saveTransitStop(stop: TransitStopSelection) = context.tvDataStore.edit {
        it[Keys.transitStopName] = stop.name
        it[Keys.transitStopPlatforms] = stop.platforms.mapTo(mutableSetOf(), ::encodeTransitPlatform)
    }

    suspend fun scheduledProgramActions(): List<ScheduledProgramAction> =
        snapshot()[Keys.scheduledProgramActions].orEmpty().mapNotNull(::decodeScheduledProgramAction)

    suspend fun saveScheduledProgramActions(actions: Collection<ScheduledProgramAction>) = context.tvDataStore.edit {
        it[Keys.scheduledProgramActions] = actions.mapTo(mutableSetOf(), ::encodeScheduledProgramAction)
    }

    suspend fun hiddenChannels(profileId: String): Set<String> {
        val key = hiddenChannelsKey(profileId)
        val stored = snapshot()[key].orEmpty()
        val now = System.currentTimeMillis()
        val activeEntries = stored.filterTo(mutableSetOf()) { entry ->
            val hiddenAt = entry.substringAfterLast('|', missingDelimiterValue = "").toLongOrNull()
            hiddenAt != null && now - hiddenAt < HIDDEN_CHANNEL_TTL_MS
        }
        if (activeEntries != stored) context.tvDataStore.edit { it[key] = activeEntries }
        return activeEntries.mapTo(mutableSetOf()) { it.substringBeforeLast('|') }
    }

    suspend fun hideChannel(profileId: String, channelId: String) = context.tvDataStore.edit { preferences ->
        val key = hiddenChannelsKey(profileId)
        val withoutChannel = preferences[key].orEmpty().filterNot { it.substringBeforeLast('|') == channelId }
        preferences[key] = (withoutChannel + "$channelId|${System.currentTimeMillis()}").toSet()
    }

    suspend fun clearHiddenChannels(profileId: String) = context.tvDataStore.edit { it.remove(hiddenChannelsKey(profileId)) }

    suspend fun channelPreferences(channelIds: List<String>): Map<String, ChannelPreference> {
        val prefs = snapshot()
        return channelIds.mapNotNull { channelId ->
            val number = prefs[intPreferencesKey("channel.$channelId.number")] ?: return@mapNotNull null
            channelId to ChannelPreference(
                channelId,
                number,
                prefs[booleanPreferencesKey("channel.$channelId.favorite")] ?: false,
            )
        }.toMap()
    }

    suspend fun saveChannelPreferences(preferences: List<ChannelPreference>) {
        require(preferences.all { it.number in 1..999 }) { "Kanalinumber peab olema vahemikus 1–999" }
        require(preferences.map(ChannelPreference::number).distinct().size == preferences.size) { "Kanalinumbrid peavad olema erinevad" }
        context.tvDataStore.edit {
            preferences.forEach { preference ->
                it[intPreferencesKey("channel.${preference.channelId}.number")] = preference.number
                it[booleanPreferencesKey("channel.${preference.channelId}.favorite")] = preference.favorite
            }
        }
    }

    private fun hiddenChannelsKey(profileId: String) = stringSetPreferencesKey("hidden_channels.$profileId")
}

private fun encodeTransitPlatform(platform: TransitStopPlatform): String = listOf(
    platform.id,
    platform.code,
    platform.latitude,
    platform.longitude,
).joinToString("|")

private fun decodeTransitPlatform(value: String): TransitStopPlatform? {
    val parts = value.split('|')
    if (parts.size != 4 || parts[0].isBlank()) return null
    return TransitStopPlatform(
        id = parts[0],
        code = parts[1],
        latitude = parts[2].toDoubleOrNull() ?: return null,
        longitude = parts[3].toDoubleOrNull() ?: return null,
    )
}

internal fun encodeScheduledProgramAction(action: ScheduledProgramAction): String = listOf(
    action.programId,
    action.channelId,
    action.startsAtEpochMs,
    if (action.reminder) 1 else 0,
    if (action.autoTune) 1 else 0,
).joinToString("|")

internal fun decodeScheduledProgramAction(value: String): ScheduledProgramAction? {
    val parts = value.split('|')
    if (parts.size != 5 || parts[0].isBlank() || parts[1].isBlank()) return null
    return ScheduledProgramAction(
        programId = parts[0],
        channelId = parts[1],
        startsAtEpochMs = parts[2].toLongOrNull() ?: return null,
        reminder = parts[3] == "1",
        autoTune = parts[4] == "1",
    ).takeIf { it.reminder || it.autoTune }
}

private const val HIDDEN_CHANNEL_TTL_MS = 6 * 60 * 60 * 1_000L
