package ee.local.go3tvplus.data

import androidx.room.withTransaction
import ee.local.go3tvplus.data.local.AppDatabase
import ee.local.go3tvplus.data.local.TvPreferences
import ee.local.go3tvplus.data.local.ChannelPreference
import ee.local.go3tvplus.data.local.ScheduledProgramAction
import ee.local.go3tvplus.data.local.toDomain
import ee.local.go3tvplus.data.local.toEntity
import ee.local.go3tvplus.domain.Channel
import ee.local.go3tvplus.domain.Go3Gateway
import ee.local.go3tvplus.domain.Go3Failure
import ee.local.go3tvplus.domain.PlaybackTicket
import ee.local.go3tvplus.domain.Profile
import ee.local.go3tvplus.domain.Program
import ee.local.go3tvplus.domain.ProgramWindow
import ee.local.go3tvplus.domain.WeatherLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

class TvRepository(
    private val gateway: Go3Gateway,
    private val auth: AuthCoordinator,
    private val database: AppDatabase,
    private val preferences: TvPreferences,
    private val weatherGateway: OpenMeteoWeatherGateway,
) {
    val channels: Flow<List<Channel>> = database.tvDao().observeChannels().map { rows -> rows.map { it.toDomain() } }
    val programs: Flow<List<Program>> = database.tvDao()
        .observePrograms(Instant.now().minus(Duration.ofDays(7)).toEpochMilli(), Instant.now().plus(Duration.ofDays(2)).toEpochMilli())
        .map { rows -> ProgramWindow.deduplicateSchedule(rows.map { it.toDomain() }) }
        .flowOn(Dispatchers.Default)
    val guide: Flow<Pair<List<Channel>, List<Program>>> = combine(channels, programs, ::Pair)

    suspend fun profiles(): List<Profile> = gateway.profiles(auth.validTokens().accessToken)

    suspend fun refresh(profileId: String) {
        val token = auth.validTokens().accessToken
        val freshChannels = try {
            withTransientRetry(listOf(1_000L, 3_000L, 7_000L)) {
                withContext(Dispatchers.Default) {
                    gateway.channels(token, profileId).filter(Channel::entitled)
                }
            }
        } catch (error: Exception) {
            throw Go3Failure.Unavailable("Kanalite laadimine: ${error.message ?: "tundmatu viga"}", error)
        }
        database.withTransaction {
            database.tvDao().clearChannels()
            database.tvDao().replaceChannels(freshChannels.mapIndexed { index, channel -> channel.toEntity(index) })
        }
        refreshPrograms(profileId)
    }

    suspend fun refreshPrograms(profileId: String): List<Program> {
        val token = auth.validTokens().accessToken
        val now = Instant.now()
        val freshPrograms = try {
            withTransientRetry(listOf(5_000L, 10_000L)) {
                withContext(Dispatchers.Default) {
                    gateway.programs(
                        token,
                        profileId,
                        now.minus(Duration.ofDays(7)),
                        now.plus(Duration.ofDays(2)),
                    )
                }
            }
        } catch (error: Exception) {
            throw Go3Failure.Unavailable("Telekava laadimine: ${error.message ?: "tundmatu viga"}", error)
        }
        database.withTransaction {
            database.tvDao().clearPrograms()
            database.tvDao().replacePrograms(freshPrograms.map(Program::toEntity))
            database.tvDao().prunePrograms(now.minus(Duration.ofDays(8)).toEpochMilli())
        }
        return freshPrograms
    }

    /** Refresh only the selected schedule slot when Go3 has assigned its recording ID after broadcast start. */
    suspend fun refreshProgramSlot(profileId: String, program: Program): List<Program> {
        val token = auth.validTokens().accessToken
        val freshPrograms = try {
            withTransientRetry(listOf(1_000L, 3_000L)) {
                withContext(Dispatchers.Default) {
                    gateway.programs(
                        token,
                        profileId,
                        program.startsAt.minus(Duration.ofMinutes(15)),
                        program.endsAt.plus(Duration.ofMinutes(15)),
                    )
                }
            }
        } catch (error: Exception) {
            throw Go3Failure.Unavailable("Saate andmete uuendamine: ${error.message ?: "tundmatu viga"}", error)
        }
        database.withTransaction {
            database.tvDao().deleteProgramSlot(
                program.channelId,
                program.startsAt.toEpochMilli(),
                program.endsAt.toEpochMilli(),
            )
            database.tvDao().replacePrograms(freshPrograms.map(Program::toEntity))
        }
        return freshPrograms
    }

    private suspend fun <T> withTransientRetry(
        retryDelaysMs: List<Long>,
        block: suspend () -> T,
    ): T {
        var lastError: Go3Failure.Unavailable? = null
        repeat(retryDelaysMs.size + 1) { attempt ->
            try {
                return block()
            } catch (error: Go3Failure.Unavailable) {
                lastError = error
                retryDelaysMs.getOrNull(attempt)?.let { delay(it) }
            }
        }
        throw lastError ?: Go3Failure.Unavailable("Go3 ühendus ebaõnnestus")
    }

    suspend fun liveTicket(profileId: String, channelId: String): PlaybackTicket =
        gateway.liveTicket(auth.validTokens().accessToken, profileId, channelId)

    suspend fun catchupTicket(profileId: String, programId: String): PlaybackTicket =
        gateway.catchupTicket(auth.validTokens().accessToken, profileId, programId)

    suspend fun closePlayback(sessionId: String?) {
        if (sessionId == null) return
        runCatching { gateway.closePlayback(auth.validTokens().accessToken, sessionId) }
    }

    suspend fun prolongPlayback(sessionId: String) =
        gateway.prolongPlayback(auth.validTokens().accessToken, sessionId)

    suspend fun lastChannelId() = preferences.lastChannelNow()
    suspend fun saveLastChannel(id: String) = preferences.saveLastChannel(id)
    suspend fun selectedProfileId() = preferences.selectedProfileNow()
    suspend fun saveSelectedProfile(id: String) = preferences.saveSelectedProfile(id)
    suspend fun playbackPreferences() = preferences.playbackPreferencesNow()
    suspend fun showClock() = preferences.showClockNow()
    suspend fun channelInfoSeconds() = preferences.channelInfoSecondsNow()
    suspend fun seekOverlaySeconds() = preferences.seekOverlaySecondsNow()
    suspend fun seekStepSeconds() = preferences.seekStepSecondsNow()
    suspend fun savePreferredAudio(language: String) = preferences.savePreferredAudio(language)
    suspend fun savePreferredSubtitle(language: String?) = preferences.savePreferredSubtitle(language)
    suspend fun saveShowClock(show: Boolean) = preferences.saveShowClock(show)
    suspend fun saveChannelInfoSeconds(seconds: Int) = preferences.saveChannelInfoSeconds(seconds)
    suspend fun saveSeekOverlaySeconds(seconds: Int) = preferences.saveSeekOverlaySeconds(seconds)
    suspend fun saveSeekStepSeconds(seconds: Int) = preferences.saveSeekStepSeconds(seconds)
    suspend fun weatherLocation() = preferences.weatherLocationNow()
    suspend fun saveWeatherLocation(location: WeatherLocation) = preferences.saveWeatherLocation(location)
    suspend fun searchWeatherLocations(query: String) = weatherGateway.searchLocations(query)
    suspend fun weatherForecast(location: WeatherLocation) = weatherGateway.forecast(location)
    suspend fun scheduledProgramActions() = preferences.scheduledProgramActionsNow()
    suspend fun saveScheduledProgramActions(actions: Collection<ScheduledProgramAction>) =
        preferences.saveScheduledProgramActions(actions)
    suspend fun hiddenChannelIds(profileId: String) = preferences.hiddenChannelsNow(profileId)
    suspend fun hideChannel(profileId: String, channelId: String) = preferences.hideChannel(profileId, channelId)
    suspend fun clearHiddenChannels(profileId: String) = preferences.clearHiddenChannels(profileId)
    suspend fun channelPreferences(channels: List<Channel>) = preferences.channelPreferencesNow(channels.map(Channel::id))
    suspend fun saveChannelPreference(preference: ChannelPreference) = preferences.saveChannelPreference(preference)
    suspend fun saveChannelPreferences(channelPreferences: List<ChannelPreference>) = preferences.saveChannelPreferences(channelPreferences)
}
