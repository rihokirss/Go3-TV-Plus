package ee.local.go3tvplus.data

import androidx.room.withTransaction
import ee.local.go3tvplus.data.local.AppDatabase
import ee.local.go3tvplus.data.local.toDomain
import ee.local.go3tvplus.data.local.toEntity
import ee.local.go3tvplus.domain.Channel
import ee.local.go3tvplus.domain.Go3Failure
import ee.local.go3tvplus.domain.Go3Gateway
import ee.local.go3tvplus.domain.PlaybackTicket
import ee.local.go3tvplus.domain.Profile
import ee.local.go3tvplus.domain.Program
import ee.local.go3tvplus.domain.ProgramWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/** Go3 andmete vahemälu: kanalid ja saatekava Roomis, piletid otse teenusest. */
class TvRepository(
    private val gateway: Go3Gateway,
    private val auth: AuthCoordinator,
    private val database: AppDatabase,
) {
    private val dao get() = database.tvDao()

    val channels: Flow<List<Channel>> = dao.observeChannels().map { rows -> rows.map { it.toDomain() } }
    val programs: Flow<List<Program>> = dao.observePrograms()
        .map { rows -> ProgramWindow.deduplicateSchedule(rows.map { it.toDomain() }) }
        .flowOn(Dispatchers.Default)

    suspend fun profiles(): List<Profile> = gateway.profiles(auth.accessToken())

    suspend fun refresh(profileId: String) {
        val token = auth.accessToken()
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
            dao.clearChannels()
            dao.replaceChannels(freshChannels.mapIndexed { index, channel -> channel.toEntity(index) })
        }
        refreshPrograms(profileId)
    }

    /**
     * Tühja vahemälu korral laetakse 7 päeva minevikku, hiljem ainult eilsest edasi.
     * Varasemad read jäävad vahemällu ja pügatakse alles 8 päeva vanuselt.
     */
    suspend fun refreshPrograms(profileId: String): List<Program> {
        val token = auth.accessToken()
        val now = Instant.now()
        val from = now.minus(if (dao.countPrograms() == 0) HISTORY_ON_FIRST_LOAD else HISTORY_ON_REFRESH)
        val until = now.plus(FUTURE_WINDOW)
        val freshPrograms = try {
            withTransientRetry(listOf(5_000L, 10_000L)) {
                withContext(Dispatchers.Default) { gateway.programs(token, profileId, from, until) }
            }
        } catch (error: Exception) {
            throw Go3Failure.Unavailable("Telekava laadimine: ${error.message ?: "tundmatu viga"}", error)
        }
        database.withTransaction {
            dao.deleteProgramsOverlapping(from.toEpochMilli(), until.toEpochMilli())
            dao.replacePrograms(freshPrograms.map(Program::toEntity))
            dao.prunePrograms(now.minus(RETENTION).toEpochMilli())
        }
        return freshPrograms
    }

    /** Refresh only the selected schedule slot when Go3 has assigned its recording ID after broadcast start. */
    suspend fun refreshProgramSlot(profileId: String, program: Program): List<Program> {
        val token = auth.accessToken()
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
            dao.deleteProgramSlot(program.channelId, program.startsAt.toEpochMilli(), program.endsAt.toEpochMilli())
            dao.replacePrograms(freshPrograms.map(Program::toEntity))
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
        gateway.liveTicket(auth.accessToken(), profileId, channelId)

    suspend fun catchupTicket(profileId: String, programId: String): PlaybackTicket =
        gateway.catchupTicket(auth.accessToken(), profileId, programId)

    suspend fun closePlayback(sessionId: String?) {
        if (sessionId == null) return
        runCatching { gateway.closePlayback(auth.accessToken(), sessionId) }
    }

    suspend fun prolongPlayback(sessionId: String) = gateway.prolongPlayback(auth.accessToken(), sessionId)

    private companion object {
        val HISTORY_ON_FIRST_LOAD: Duration = Duration.ofDays(7)
        val HISTORY_ON_REFRESH: Duration = Duration.ofDays(1)
        val FUTURE_WINDOW: Duration = Duration.ofDays(2)
        val RETENTION: Duration = Duration.ofDays(8)
    }
}
