package ee.local.go3tvplus.catalog

import androidx.room.withTransaction
import ee.local.go3tvplus.data.AuthCoordinator
import ee.local.go3tvplus.data.local.AppDatabase
import ee.local.go3tvplus.domain.Go3Gateway
import ee.local.go3tvplus.domain.Go3Failure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Metadata and rights never request a playback ticket. Progress uses a durable profile-scoped outbox; watchlists stay local. */
class CatalogRepository(private val gateway: Go3Gateway, private val auth: AuthCoordinator, private val db: AppDatabase, private val persistenceScope: CoroutineScope) {
    fun persist(row: ProgressEntity) { persistenceScope.launch(Dispatchers.IO) { dao.saveLocalProgress(row) } }
    val dao get() = db.catalogDao()
    private val historyCycles = Mutex()
    private val historyPullTimes = mutableMapOf<String, Long>()
    private val historyStatuses = MutableStateFlow<Map<String, CatalogHistoryStatus>>(emptyMap())
    fun historyStatus(profile: String): Flow<CatalogHistoryStatus> = historyStatuses.map { it[profile] ?: CatalogHistoryStatus() }.distinctUntilChanged()
    private fun historyStatus(profile: String, update: (CatalogHistoryStatus) -> CatalogHistoryStatus) {
        historyStatuses.update { it + (profile to update(it[profile] ?: CatalogHistoryStatus())) }
    }
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    suspend fun runHistorySync(profile: String) {
        delay(4000)
        merge(dao.historyQueue(profile).map { Unit }, flow { while (true) { emit(Unit); delay(10_000) } })
            .debounce(750).conflate().collect {
                while (!prefetchAllowed) delay(1000)
                try { syncHistory(profile) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* Durable outbox retries on the next tick or local change. */ }
            }
    }
    suspend fun syncHistory(profile: String, forcePull: Boolean = false) = historyCycles.withLock {
        historyStatus(profile) { it.copy(syncing = true, error = null) }
        try {
            if (forcePull || System.currentTimeMillis() - (historyPullTimes[profile] ?: 0) >= 60_000) {
                val remote = request { gateway.catalogHistory(it, profile) }
                mergeHistory(profile, remote)
                historyPullTimes[profile] = System.currentTimeMillis()
            }
            for (pending in dao.historyQueue(profile).first()) {
                request { gateway.saveCatalogHistory(it, profile, pending.contentId, pending.positionMs) }
                // A newer pause/seek written during upload must remain queued.
                dao.acknowledgeHistory(profile, pending.contentId, pending.updatedAt, pending.positionMs)
            }
            val remaining = dao.historyQueue(profile).first().size
            historyStatus(profile) { it.copy(pending = remaining) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            historyPullTimes.remove(profile)
            val remaining = dao.historyQueue(profile).first().size
            val message = when (error) {
                is Go3Failure.Authentication -> "Go3 vaatamisjärje jaoks tuleb konto uuesti siduda"
                is Go3Failure.HttpStatus, is IllegalArgumentException -> "Go3 vaatamisjärje uuendamine ebaõnnestus"
                else -> "Go3 vaatamisjärg ootab ühendust"
            }
            historyStatus(profile) { it.copy(error = message, pending = remaining) }
            throw error
        } finally { historyStatus(profile) { it.copy(syncing = false) } }
    }
    private suspend fun mergeHistory(profile: String, records: List<CatalogHistoryRecord>) {
        var newMetadata = false
        val knownIds = dao.revisions(profile).first().mapTo(hashSetOf()) { it.id }
        val previousProgress = dao.progressSnapshot(profile).associateBy { it.contentId }
        for (record in records) {
            val previous = previousProgress[record.id]
            // Repeated snapshots usually contain exactly the same history. Avoid
            // hundreds of no-op SQLite transactions on every background poll.
            if (record.id in knownIds && previous != null && record.updatedAt <= previous.remoteModifiedAt &&
                record.completed == previous.completed && record.continueEligible == previous.continueEligible) continue
            newMetadata = newMetadata || record.id !in knownIds
            record.series?.takeIf { it.text("id") != null && it.text("title") != null }?.let { series ->
                save(profile, listOf(JsonObject(series + ("type" to JsonPrimitive("SERIAL")))), false)
            }
            save(profile, listOf(record.item), false, record.seriesId, record.seasonId)
            db.withTransaction {
                val local = dao.progressItem(profile, record.id)
                val pending = dao.queuedHistory(profile, record.id)
                val newer = local == null || if (pending != null) remoteHistoryWins(record.updatedAt, local.updatedAt, true) else record.updatedAt > local.remoteModifiedAt
                if (newer) {
                    dao.saveProgress(ProgressEntity(profile, record.id, record.seriesId, record.positionMs, record.durationMs, record.updatedAt, record.completed, record.continueEligible, record.updatedAt))
                    if (pending != null) dao.acknowledgeHistory(profile, pending.contentId, pending.updatedAt, pending.positionMs)
                } else if (pending == null && local != null && kotlin.math.abs(local.positionMs - record.positionMs) < 1000) {
                    // Go3 may age an item out of Continue without changing its play time.
                    val refreshed = local.copy(completed = record.completed, continueEligible = record.continueEligible)
                    if (refreshed != local) dao.saveProgress(refreshed)
                }
            }
        }
        // A successful full snapshot also carries removals made in Go3. Offline
        // changes remain queued, so they cannot disappear with a remote deletion.
        val remoteIds = records.mapTo(hashSetOf()) { it.id }
        db.withTransaction {
            for (local in dao.progressSnapshot(profile)) {
                if (local.contentId !in remoteIds && dao.queuedHistory(profile, local.contentId) == null) dao.deleteProgress(profile, local.contentId)
            }
        }
        if (newMetadata && knownRights(profile) == null) refreshRights(profile)
    }
    private val requests = Mutex()
    private val seasonEpisodes = java.util.concurrent.ConcurrentHashMap<String, List<CatalogTitle>>()
    private fun seasonKey(profile: String, series: String, season: String) = "$profile:$series:$season"
    @Volatile private var currentRights: Pair<String, CatalogRights>? = null
    private fun knownRights(profile: String) = currentRights?.takeIf { it.first == profile && System.currentTimeMillis() - it.second.checkedAt < 6 * 3600_000L }?.second?.ids
    @Volatile var prefetchAllowed = true
    private suspend fun <T> request(block: suspend (String) -> T): T = withContext(Dispatchers.Default) { requests.withLock {
        currentCoroutineContext().ensureActive()
        block(auth.accessToken()).also { currentCoroutineContext().ensureActive() }
    } }
    private data class Decoded(val rowid: Long, val updatedAt: Long, val title: CatalogTitle?)
    private val decoded = java.util.concurrent.ConcurrentHashMap<String, Decoded>()
    private fun decode(row: CatalogEntity): CatalogTitle? {
        val key = row.profileId + ":" + row.id
        decoded[key]?.takeIf { it.rowid == row.rowid && it.updatedAt == row.updatedAt }?.let { return it.title }
        val item = runCatching { parseCatalogTitle(Json.parseToJsonElement(row.json).jsonObject, seriesId = row.seriesId, seasonId = row.seasonId) }.getOrNull()
        decoded[key] = Decoded(row.rowid, row.updatedAt, item)
        return item
    }
    private fun withRights(profile: String, content: Flow<List<CatalogTitle>>): Flow<List<CatalogTitle>> =
        combine(content, dao.rights(profile), dao.sync(profile)) { items, rights, sync ->
            val ids = if (sync != null && System.currentTimeMillis() - sync.rightsCheckedAt < 6 * 3600_000L) rights.associate { it.id to it.till } else null
            items.map { it.copy(right = resolveViewingRight(it, ids)) }
        }.flowOn(Dispatchers.Default)

    fun titles(profile: String): Flow<List<CatalogTitle>> = withRights(profile,
        dao.titles(profile).map { rows -> rows.mapNotNull(::decode) })

    fun popularMovieIds(profile: String): Flow<List<String>> = dao.sync(profile).map { sync ->
        runCatching { Json.parseToJsonElement(sync?.popularMovieIds ?: "[]").jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrDefault(emptyList())
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeTitles(profile: String): Flow<List<CatalogTitle>> {
        var published = false
        val content = dao.revisions(profile).transformLatest { revisions ->
            val items = ArrayList<CatalogTitle>(revisions.size)
            revisions.chunked(120).forEachIndexed { index, chunk ->
                currentCoroutineContext().ensureActive()
                val changed = chunk.filter { ref ->
                    val cached = decoded[profile + ":" + ref.id]
                    cached == null || cached.rowid != ref.rowid || cached.updatedAt != ref.updatedAt
                }
                if (changed.isNotEmpty()) dao.titlesByIds(profile, changed.map { it.id }).forEach(::decode)
                chunk.forEach { ref -> decoded[profile + ":" + ref.id]?.title?.let(items::add) }
                // Read the first visible screen before the rest, without copying every JSON
                // blob on startup or whenever a single episode/detail changes.
                if (!published && index == 0) { emit(items.toList()); published = true }
                if (changed.isNotEmpty()) delay(32) else yield()
            }
            emit(items)
            published = true
        }
        return withRights(profile, content)
    }
    suspend fun refreshRights(profile: String) {
        val rights = request { gateway.catalogRights(it, profile) }
        currentRights = profile to rights
        val knownIds = dao.revisions(profile).first().mapTo(hashSetOf()) { it.id }
        db.withTransaction {
            dao.clearRights(profile)
            // Persist only known catalogue IDs; the endpoint also contains TV recording rights.
            rights.ids.entries.filter { it.key in knownIds }.chunked(500).forEach { chunk -> dao.saveRights(chunk.map { RightEntity(profile, it.key, it.value, rights.checkedAt) }) }
            val previous = dao.syncSnapshot(profile) ?: CatalogSyncEntity(profile, 0)
            dao.saveSync(previous.copy(rightsCheckedAt = rights.checkedAt))
        }
    }
    suspend fun needsRefresh(profile: String): Boolean {
        val sync = dao.syncSnapshot(profile) ?: return true
        val oldest = minOf(sync.catalogCheckedAt, sync.genresCheckedAt)
        val now = System.currentTimeMillis()
        return oldest <= 0 || now < oldest || now - oldest > 3600_000
    }
    suspend fun refresh(profile: String) {
        refreshRights(profile)
        var offset = 0
        val seen = hashSetOf<String>()
        repeat(100) {
            while (!prefetchAllowed) delay(1000)
            val page = request { gateway.catalogPage(it, profile, offset, if (offset == 0) 120 else 400) }
            if (ee.local.go3tvplus.BuildConfig.DEBUG) android.util.Log.i("CatalogCache", "page offset=$offset consumed=${page.consumed} total=${page.total}")
            save(profile, page.items, detailed = false)
            seen += page.items.mapNotNull { it.text("id") }
            offset += page.consumed
            if (offset >= page.total) {
                refreshGenres(profile, seen)
                // Only a completed snapshot may retire removed titles; interrupted pages preserve the cache.
                val goneIds = dao.revisions(profile).first().filter { it.kind != CatalogKind.EPISODE.name && it.id !in seen }.map { it.id }
                goneIds.chunked(120).forEach { ids ->
                    dao.saveTitles(dao.titlesByIds(profile, ids).map { row -> row.copy(json = JsonObject(Json.parseToJsonElement(row.json).jsonObject + ("active" to JsonPrimitive(false))).toString(), updatedAt = System.currentTimeMillis()) })
                }
                try { refreshPopularMovies(profile) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    // Preserve the cached order; never expose credentials/URLs in diagnostics.
                    if (ee.local.go3tvplus.BuildConfig.DEBUG) android.util.Log.i("CatalogCache", "Popularity unavailable: ${error.javaClass.simpleName}")
                }
                val sync = dao.syncSnapshot(profile) ?: CatalogSyncEntity(profile, 0)
                val checkedAt = System.currentTimeMillis()
                dao.saveSync(sync.copy(catalogCheckedAt = checkedAt, genresCheckedAt = checkedAt))
                return
            }
            if (page.consumed == 0) throw Go3Failure.Unavailable("Kataloogi värskendamine jäi pooleli")
            // Allow an immediately requested detail to run before the next background page.
            delay(150)
        }
        throw Go3Failure.Unavailable("Kataloogi värskendamine jäi pooleli")
    }
    private suspend fun refreshGenres(profile: String, catalogIds: Set<String>) {
        var offset = 0
        repeat(100) {
            while (!prefetchAllowed) delay(1000)
            val page = request { gateway.catalogGenrePage(it, profile, offset, if (offset == 0) 120 else 400) }
            // Merge only genres into known catalogue records. Search-index documents
            // omit descriptions/original titles and must not replace those fields.
            val updates = page.items.mapNotNull { raw ->
                val id = raw.text("id")?.takeIf { it in catalogIds } ?: return@mapNotNull null
                if (raw["genres"] !is JsonArray) return@mapNotNull null
                buildJsonObject { put("id", id); put("genres", raw.getValue("genres")) }
            }
            save(profile, updates, detailed = false)
            offset += page.consumed
            if (offset >= page.total) return
            if (page.consumed == 0) throw Go3Failure.Unavailable("Žanride laadimine jäi pooleli")
            delay(150)
        }
        throw Go3Failure.Unavailable("Žanride laadimine jäi pooleli")
    }
    private suspend fun refreshPopularMovies(profile: String) {
        while (!prefetchAllowed) delay(1000)
        val movies = request { gateway.catalogPopularMovies(it, profile) }
        save(profile, movies, detailed = false)
        val ids = movies.mapNotNull { it.text("id") }.distinct()
        val sync = dao.syncSnapshot(profile) ?: CatalogSyncEntity(profile, 0)
        dao.saveSync(sync.copy(popularMovieIds = JsonArray(ids.map(::JsonPrimitive)).toString()))
    }
    private suspend fun save(profile: String, items: List<JsonObject>, detailed: Boolean, series: String? = null, season: String? = null) {
        // Bound each write transaction and use batch DAO operations: per-title suspend
        // queries used to hold the writer for tens of seconds on older televisions.
        for (chunk in items.chunked(64)) {
            db.withTransaction {
                val previous = dao.titlesByIds(profile, chunk.mapNotNull { it.text("id") }).associateBy { it.id }
                val rows = chunk.mapNotNull { raw ->
                    val existing = previous[raw.text("id")]
                    val merged = existing?.let { JsonObject(Json.parseToJsonElement(it.json).jsonObject + raw) } ?: raw
                    val compact = compactCatalogJson(merged)
                    val parent = series ?: existing?.seriesId
                    val seasonId = season ?: existing?.seasonId
                    val json = compact.toString()
                    val isDetailed = detailed || existing?.detailed == true
                    if (existing != null && json == existing.json && parent == existing.seriesId && seasonId == existing.seasonId && isDetailed == existing.detailed) return@mapNotNull null
                    val item = parseCatalogTitle(compact, seriesId = parent, seasonId = seasonId) ?: return@mapNotNull null
                    CatalogEntity(profileId = profile, id = item.id, kind = item.kind.name, normalizedText = item.searchText,
                        json = json, seriesId = parent, seasonId = seasonId, detailed = isDetailed, updatedAt = System.currentTimeMillis())
                }
                dao.saveTitles(rows)
                knownRights(profile)?.let { rights ->
                    dao.saveRights(rows.filter { rights.containsKey(it.id) }.map { RightEntity(profile, it.id, rights[it.id], currentRights!!.second.checkedAt) })
                }
            }
            yield()
        }
    }
    suspend fun detail(profile: String, id: String) {
        save(profile, listOf(request { gateway.catalogDetail(it, profile, id) }), true)
    }
    suspend fun seasons(profile: String, series: String, refresh: Boolean = true): List<CatalogSeason> {
        if (refresh) {
            val fresh = request { gateway.catalogSeasons(it, profile, series) }
            fresh.forEach { seasonEpisodes.remove(seasonKey(profile, series, it.id)) }
            dao.saveSeasons(fresh.map { SeasonEntity(profile, it.id, series, it.number, it.title) })
        }
        return dao.seasons(profile, series).map { CatalogSeason(it.id, it.seriesId, it.number, it.title) }
    }
    suspend fun episodes(profile: String, series: String, season: String) {
        if (knownRights(profile) == null) refreshRights(profile)
        val raw = request { gateway.catalogEpisodes(it, profile, series, season) }
        save(profile, raw, true, series, season)
        dao.markSeasonComplete(profile, season)
        seasonEpisodes[seasonKey(profile, series, season)] = raw.mapNotNull { parseCatalogTitle(it, seriesId = series, seasonId = season) }.map { it.copy(right = resolveViewingRight(it, knownRights(profile))) }
    }
    suspend fun completeEpisodes(profile: String, series: String, season: String): List<CatalogTitle> {
        val key = seasonKey(profile, series, season)
        seasonEpisodes[key]?.let { items -> return items.map { it.copy(right = resolveViewingRight(it, knownRights(profile))) } }
        if (dao.completeSeason(profile, series, season) != true) episodes(profile, series, season)
        val items = seasonEpisodes[key] ?: cachedEpisodes(profile, series, season).also { seasonEpisodes[key] = it }
        return items
    }
    suspend fun cachedEpisodes(profile: String, series: String, season: String): List<CatalogTitle> = withContext(Dispatchers.Default) {
        val sync = dao.syncSnapshot(profile)
        val rights = if (sync != null && System.currentTimeMillis() - sync.rightsCheckedAt < 6 * 3600_000L)
            dao.rights(profile).first().associate { it.id to it.till } else null
        dao.episodes(profile, series, season).mapNotNull(::decode).map { it.copy(right = resolveViewingRight(it, rights)) }
    }
    // Episodes use the MOVIE ticket contract too (verified on Sony with the normal Widevine licence).
    suspend fun ticket(profile: String, id: String) = gateway.movieTicket(auth.accessToken(), profile, id)
    suspend fun search(profile: String, query: String): Set<String>? = catalogSearchQuery(query)?.let { dao.search(profile, it).toSet() }
}
