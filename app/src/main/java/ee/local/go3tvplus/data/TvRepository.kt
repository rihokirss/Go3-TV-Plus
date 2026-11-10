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
import ee.local.go3tvplus.domain.WatchNowResolver
import ee.local.go3tvplus.domain.WatchNowSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/** Go3 andmete vahemälu: kanalid ja saatekava Roomis, piletid otse teenusest. */
class TvRepository(
    private val gateway: Go3Gateway,
    private val auth: AuthCoordinator,
    private val database: AppDatabase,
    scope: CoroutineScope,
) {
    private val dao get() = database.tvDao()
    private val movieCatalog = MovieCatalogCache(scope, { profileId ->
        withContext(Dispatchers.Default) { gateway.movies(auth.accessToken(), profileId) }
    })
    private val watchNowCache = WatchNowLookupCache()

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
        // A schema-2 cache also needs a full history refresh to backfill episode identities.
        val needsHistory = dao.countPrograms() == 0 || dao.countProgramsWithIdentityMetadata() == 0
        val from = now.minus(if (needsHistory) HISTORY_ON_FIRST_LOAD else HISTORY_ON_REFRESH)
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

    suspend fun watchNowSources(profileId: String, program: Program, allowedChannelIds: Set<String>): List<WatchNowSource> =
        watchNowSourcesBatch(profileId, listOf(program), allowedChannelIds).last()[program].orEmpty()

    /** A viewport uses one local identity query and one shared catalogue, never one network request per cell. */
    fun watchNowSourcesBatch(
        profileId: String,
        programs: List<Program>,
        allowedChannelIds: Set<String>,
    ): Flow<Map<Program, List<WatchNowSource>>> = flow {
        val resolved = linkedMapOf<Program, List<WatchNowSource>>()
        val now = Instant.now()
        val missing = programs.distinctBy(Program::id).filter { program ->
            val cached = watchNowCache.get(profileId, program, allowedChannelIds)
            if (cached != null) resolved[program] = cached
            cached == null
        }
        if (resolved.isNotEmpty()) emit(resolved.toMap())
        // Restore known matches before any catalogue request, including after process death.
        val pendingById = missing.associateBy(Program::id)
        missing.map(Program::id).chunked(400).forEach { ids ->
            dao.watchNowMatches(profileId, ids, now.toEpochMilli()).forEach savedMatch@ { row ->
                val program = pendingById[row.programId] ?: return@savedMatch
                val sources = WatchNowMatchCodec.decode(row, program, allowedChannelIds, now)
                if (sources.isNotEmpty()) {
                    resolved[program] = sources
                    watchNowCache.put(profileId, program, allowedChannelIds, sources, Instant.ofEpochMilli(row.checkedAtEpochMs))
                }
            }
        }
        if (resolved.isNotEmpty()) emit(resolved.toMap())
        val unchecked = missing.filter { watchNowCache.get(profileId, it, allowedChannelIds) == null }
        val identities = unchecked.mapNotNull { it.secondaryId?.takeIf(WatchNowResolver::validIdentity) }.distinct()
        val recordings = identities.chunked(400).flatMap { ids ->
            dao.recordingsWithIdentities(ids, now.toEpochMilli()).map { it.toDomain() }
        }.groupBy { it.epgProvider to it.secondaryId }
        val needMovies = mutableListOf<Program>()
        val checked = linkedMapOf<Program, List<WatchNowSource>>()
        fun remember(program: Program, sources: List<WatchNowSource>) {
            resolved[program] = sources
            checked[program] = sources
            watchNowCache.put(profileId, program, allowedChannelIds, sources)
        }
        suspend fun saveChecked() {
            if (checked.isEmpty()) return
            database.withTransaction {
                dao.deleteWatchNowMatches(profileId, checked.keys.map(Program::id))
                dao.saveWatchNowMatches(checked.filterValues { it.isNotEmpty() }.map { (program, sources) ->
                    WatchNowMatchCodec.encode(profileId, program, sources, Instant.now())
                })
                dao.pruneWatchNowMatches(Instant.now().toEpochMilli())
            }
            checked.clear()
        }
        for (program in unchecked) {
            val matches = WatchNowResolver.recordings(
                program, recordings[program.epgProvider to program.secondaryId].orEmpty(), allowedChannelIds, now,
            )
            when {
                matches.isNotEmpty() -> remember(program, matches)
                program.epgProvider == "T24" && program.secondaryId?.let(WatchNowResolver::validIdentity) == true -> needMovies += program
                else -> remember(program, emptyList())
            }
        }
        // Local repeat matches appear immediately, while the first film catalogue is still loading.
        saveChecked()
        emit(resolved.toMap())
        if (needMovies.isNotEmpty()) {
            val movies = movieCatalog.candidates(profileId, needMovies.mapNotNull(Program::secondaryId).toSet())
            for (program in needMovies) {
                remember(program, WatchNowResolver.movies(program, movies[program.secondaryId].orEmpty(), Instant.now()))
            }
            saveChecked()
            emit(resolved.toMap())
        }
    }.flowOn(Dispatchers.Default)

    suspend fun watchNowTicket(
        profileId: String,
        requested: Program,
        sources: List<WatchNowSource>,
        allowedChannelIds: Set<String>,
    ): Pair<Program, PlaybackTicket> {
        var lastError: Exception? = null
        for (source in sources) {
            val now = Instant.now()
            try {
                when (source) {
                    is WatchNowSource.Recording -> {
                        if (WatchNowResolver.recordings(requested, listOf(source.program), allowedChannelIds, now).isEmpty()) continue
                        return source.program to catchupTicket(profileId, source.program.id)
                    }
                    is WatchNowSource.Movie -> {
                        if (WatchNowResolver.movies(requested, listOf(source.movie), now).isEmpty()) continue
                        val ticket = gateway.movieTicket(auth.accessToken(), profileId, source.movie.id)
                        // Describe the video being watched, without placing playback on tomorrow's timeline.
                        return requested.copy(startsAt = now, endsAt = now.plusSeconds(source.movie.durationSeconds)) to ticket
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Go3Failure.NotEntitled) {
                lastError = error
            } catch (error: Go3Failure.HttpStatus) {
                if (error.statusCode != 404) throw error
                lastError = error
            }
        }
        throw lastError ?: Go3Failure.Unavailable("Seda saadet ei saa veel ette vaadata")
    }

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
