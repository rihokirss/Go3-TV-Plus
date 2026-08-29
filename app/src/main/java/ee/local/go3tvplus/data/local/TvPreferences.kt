package ee.local.go3tvplus.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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

class TvPreferences(private val context: Context) {
    private object Keys {
        val lastChannel = stringPreferencesKey("last_channel")
        val selectedProfile = stringPreferencesKey("selected_profile")
        val preferredAudio = stringPreferencesKey("preferred_audio")
        val preferredSubtitle = stringPreferencesKey("preferred_subtitle")
    }

    val lastChannel: Flow<String?> = context.tvDataStore.data.map { it[Keys.lastChannel] }
    val selectedProfile: Flow<String?> = context.tvDataStore.data.map { it[Keys.selectedProfile] }
    val preferredAudio: Flow<String> = context.tvDataStore.data.map { it[Keys.preferredAudio] ?: "et" }
    val preferredSubtitle: Flow<String?> = context.tvDataStore.data.map { it[Keys.preferredSubtitle] }

    suspend fun lastChannelNow(): String? = lastChannel.first()
    suspend fun selectedProfileNow(): String? = selectedProfile.first()
    suspend fun saveLastChannel(id: String) = context.tvDataStore.edit { it[Keys.lastChannel] = id }
    suspend fun saveSelectedProfile(id: String) = context.tvDataStore.edit { it[Keys.selectedProfile] = id }
    suspend fun playbackPreferencesNow() = PlaybackPreferences(preferredAudio.first(), preferredSubtitle.first())
    suspend fun savePreferredAudio(language: String) = context.tvDataStore.edit { it[Keys.preferredAudio] = language }
    suspend fun savePreferredSubtitle(language: String?) = context.tvDataStore.edit {
        if (language == null) it.remove(Keys.preferredSubtitle) else it[Keys.preferredSubtitle] = language
    }

    suspend fun hiddenChannelsNow(profileId: String): Set<String> {
        val key = stringSetPreferencesKey("hidden_channels.$profileId")
        val stored = context.tvDataStore.data.first()[key].orEmpty()
        val now = System.currentTimeMillis()
        val activeEntries = stored.filterTo(mutableSetOf()) { entry ->
            val hiddenAt = entry.substringAfterLast('|', missingDelimiterValue = "").toLongOrNull()
            hiddenAt != null && now - hiddenAt < HIDDEN_CHANNEL_TTL_MS
        }
        if (activeEntries != stored) context.tvDataStore.edit { it[key] = activeEntries }
        return activeEntries.mapTo(mutableSetOf()) { it.substringBeforeLast('|') }
    }

    suspend fun hideChannel(profileId: String, channelId: String) = context.tvDataStore.edit { preferences ->
        val key = stringSetPreferencesKey("hidden_channels.$profileId")
        val withoutChannel = preferences[key].orEmpty().filterNot { it.substringBeforeLast('|') == channelId }
        preferences[key] = (withoutChannel + "$channelId|${System.currentTimeMillis()}").toSet()
    }

    suspend fun clearHiddenChannels(profileId: String) = context.tvDataStore.edit {
        it.remove(stringSetPreferencesKey("hidden_channels.$profileId"))
    }

    fun channelPreference(channelId: String): Flow<ChannelPreference?> = context.tvDataStore.data.map { prefs ->
        val number = prefs[intPreferencesKey("channel.$channelId.number")] ?: return@map null
        ChannelPreference(channelId, number, prefs[booleanPreferencesKey("channel.$channelId.favorite")] ?: false)
    }

    suspend fun channelPreferencesNow(channelIds: List<String>): Map<String, ChannelPreference> {
        val prefs = context.tvDataStore.data.first()
        return channelIds.mapNotNull { channelId ->
            val number = prefs[intPreferencesKey("channel.$channelId.number")] ?: return@mapNotNull null
            channelId to ChannelPreference(
                channelId,
                number,
                prefs[booleanPreferencesKey("channel.$channelId.favorite")] ?: false,
            )
        }.toMap()
    }

    suspend fun saveChannelPreference(preference: ChannelPreference) {
        saveChannelPreferences(listOf(preference))
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
}

private const val HIDDEN_CHANNEL_TTL_MS = 6 * 60 * 60 * 1_000L
