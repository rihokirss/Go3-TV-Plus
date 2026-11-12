package ee.local.go3tvplus.catalog

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class CatalogSection(val label: String) { TV("Televisioon"), MOVIES("Filmid"), SERIES("Seriaalid"), SEARCH("Otsing"), LIST("Minu nimekiri"), SETTINGS("Seaded") }
data class CatalogFilter(val kind: CatalogKind? = null, val genre: String? = null, val year: Int? = null, val allRights: Boolean = false, val sort: CatalogSort = CatalogSort.ADDED)
data class CatalogScreenState(val key: String, val title: String, val detailId: String? = null, val grid: Boolean = false, val filter: CatalogFilter = CatalogFilter(), val group: String? = null)
data class CatalogRow(val key: String, val title: String, val items: List<CatalogTitle>)
data class CatalogUiState(
    val profile: String? = null, val visible: Boolean = false, val section: CatalogSection = CatalogSection.TV,
    val menu: Boolean = true, val entry: Int = 0, val pages: List<CatalogScreenState> = emptyList(),
    val titles: List<CatalogTitle> = emptyList(), val list: List<String> = emptyList(), val progress: Map<String, CatalogProgress> = emptyMap(),
    val seasons: Map<String, List<CatalogSeason>> = emptyMap(), val completeSeasons: Set<String> = emptySet(), val selectedSeasons: Map<String, String> = emptyMap(),
    val query: String = "", val searchIds: Set<String>? = null, val searching: Boolean = false,
    val loading: Boolean = false, val detailLoading: Boolean = false, val error: String? = null,
    val history: CatalogHistoryStatus = CatalogHistoryStatus(), val autoplay: Boolean = true, val next: CatalogTitle? = null, val countdown: Int? = null,
) {
    val page get() = pages.lastOrNull()
    val detail get() = titles.firstOrNull { it.id == page?.detailId }
    fun progressFor(item: CatalogTitle): CatalogProgress? = if (item.kind == CatalogKind.SERIES) progress.values.filter { it.seriesId == item.id }.maxByOrNull { it.updatedAt } else progress[item.id]
    fun filtered(filter: CatalogFilter = page?.filter ?: CatalogFilter()): List<CatalogTitle> {
        val candidates = titles.asSequence().filter { it.kind != CatalogKind.EPISODE }
            .filter { filter.kind == null || it.kind == filter.kind }
            .filter { filter.allRights || it.right == ViewingRight.AVAILABLE }
            .filter { filter.genre == null || filter.genre in it.genres }
            .filter { filter.year == null || it.year == filter.year }
            .filter { section != CatalogSection.LIST || it.id in list }
            .filter { section != CatalogSection.SEARCH || searchIds == null || it.id in searchIds }
        return when (filter.sort) { CatalogSort.ADDED -> candidates.sortedByDescending { it.added }; CatalogSort.TITLE -> { val collator = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("et")); candidates.sortedWith { a, b -> collator.compare(a.title, b.title) } }; CatalogSort.YEAR -> candidates.sortedByDescending { it.year ?: 0 } }.toList()
    }
    fun seriesHasMore(series: CatalogTitle): Boolean {
        if (progressFor(series)?.watched != true) return true
        val knownSeasons = seasons[series.id].orEmpty()
        val episodes = titles.filter { it.seriesId == series.id }
        // A partially loaded series cannot be declared finished.
        if (knownSeasons.isEmpty() || knownSeasons.any { season -> season.id !in completeSeasons }) return true
        return episodes.any { progress[it.id]?.watched != true }
    }
    fun rows(): List<CatalogRow> {
        val items = filtered()
        val byId = items.associateBy { it.id }
        val continuing = progress.values.sortedByDescending { it.updatedAt }.filter { it.positionMs > 0 && it.continueEligible }
            .mapNotNull { p -> byId[p.seriesId ?: p.contentId]?.takeIf { if (p.seriesId != null) seriesHasMore(it) else !p.watched } }.distinctBy { it.id }
        return buildList {
            if (continuing.isNotEmpty()) add(CatalogRow("continue", "Jätka vaatamist", continuing))
            val saved = list.mapNotNull(byId::get); if (saved.isNotEmpty()) add(CatalogRow("list", "Minu nimekiri", saved))
            if (items.isNotEmpty()) add(CatalogRow("recent", "Hiljuti lisatud", items.sortedByDescending { it.added }))
            items.flatMap { it.genres }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.forEach { (genre, _) ->
                add(CatalogRow("genre:$genre", genre, items.filter { genre in it.genres }))
            }
        }
    }
}

/** Catalogue state, navigation and requests are independent of the TV schedule. */
class CatalogViewModel(private val repository: CatalogRepository, private val scope: CoroutineScope, context: Context) {
    private val historySyncEnabled = !(ee.local.go3tvplus.BuildConfig.DEBUG && context.getSharedPreferences("local_diagnostics", Context.MODE_PRIVATE).getBoolean("suspend_history_sync", false))
    private val diagnostics = context.getSharedPreferences("local_diagnostics", Context.MODE_PRIVATE).getBoolean("playback", false)
    private var browseOpenedAtNanos = 0L
    private var remoteInputAtNanos = 0L
    fun recordRemoteInput() { remoteInputAtNanos = System.nanoTime() }
    private val prefs = context.getSharedPreferences("catalog_settings", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(CatalogUiState(autoplay = prefs.getBoolean("autoplay", true)))
    val state = mutable.asStateFlow()
    private val snapshot get() = mutable.value
    private var profileJob: Job? = null
    private var refreshJob: Job? = null
    private var detailJob: Job? = null
    private var searchJob: Job? = null
    private var countdownJob: Job? = null
    private var nextJob: Job? = null
    private var searchGeneration = 0L
    private var seriesPlayJob: Job? = null
    val focusedKeys = mutableMapOf<String, String>()
    var lastFocusAtNanos: Long = 0
        private set
    fun recordFocus(page: String, key: String) {
        if (diagnostics && browseOpenedAtNanos > 0 && page == snapshot.section.name) {
            android.util.Log.i("Go3Catalog", "cachedViewFocusMs=${(System.nanoTime() - browseOpenedAtNanos) / 1_000_000.0}")
            browseOpenedAtNanos = 0L
        }
        focusedKeys[page] = key
        lastFocusAtNanos = System.nanoTime()
        val inputAt = remoteInputAtNanos
        remoteInputAtNanos = 0L
        val elapsed = (lastFocusAtNanos - inputAt) / 1_000_000.0
        if (diagnostics && inputAt > 0 && elapsed in 0.0..1000.0) android.util.Log.i("Go3Catalog", "focusMs=$elapsed")
    }
    val scrollPositions = mutableMapOf<String, Pair<Int, Int>>()
    var onPlay: (CatalogTitle, Long) -> Unit = { _, _ -> }
    var onTv: () -> Unit = {}
    var onSettings: () -> Unit = {}

    fun setProfile(profile: String?) {
        if (snapshot.profile == profile) return
        seriesPlayJob?.cancel(); profileJob?.cancel(); refreshJob?.cancel(); detailJob?.cancel(); searchJob?.cancel(); nextJob?.cancel(); cancelNext()
        searchGeneration++; focusedKeys.clear(); scrollPositions.clear()
        mutable.value = CatalogUiState(profile = profile, autoplay = snapshot.autoplay)
        if (profile == null) return
        profileJob = scope.launch {
            launch { if (historySyncEnabled) repository.runHistorySync(profile) }
            launch { repository.historyStatus(profile).collect { value -> mutable.update { it.copy(history = value) } } }
            launch { repository.observeTitles(profile).collect { values ->
                mutable.update { it.copy(titles = values) }
                if (snapshot.query.isNotBlank()) query(snapshot.query)
            } }
            launch { repository.dao.watchlist(profile).collect { values -> mutable.update { it.copy(list = values.map { it.id }) } } }
            launch { repository.dao.progress(profile).collect { values -> mutable.update { it.copy(progress = values.associate { it.contentId to it.domain() }) } } }
        }
    }
    fun openMenu() { mutable.update { it.copy(visible = true, menu = true) } }
    fun contentFocus() { mutable.update { it.copy(menu = false) } }
    fun select(section: CatalogSection) {
        if (section == CatalogSection.TV) { mutable.update { it.copy(visible = false, section = section, pages = emptyList()) }; onTv(); return }
        if (section == CatalogSection.SETTINGS) { mutable.update { it.copy(visible = false) }; onSettings(); return }
        if (diagnostics) browseOpenedAtNanos = System.nanoTime()
        val kind = when(section) { CatalogSection.MOVIES -> CatalogKind.MOVIE; CatalogSection.SERIES -> CatalogKind.SERIES; else -> null }
        mutable.update { it.copy(visible = true, menu = false, section = section, entry = it.entry + 1, pages = listOf(CatalogScreenState(section.name, section.label, grid = section == CatalogSection.SEARCH || section == CatalogSection.LIST, filter = CatalogFilter(kind = kind))), error = null) }
        scope.launch {
            val last = repository.dao.syncSnapshot(snapshot.profile ?: return@launch)?.catalogCheckedAt ?: 0
            if (System.currentTimeMillis() - last > 3600_000) refresh()
        }
    }
    fun refresh() {
        val profile = snapshot.profile ?: return
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            mutable.update { it.copy(loading = true, error = null) }
            try { repository.refresh(profile) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "Kataloogi laadimine ebaõnnestus") } }
            finally { if (snapshot.profile == profile) mutable.update { it.copy(loading = false) } }
        }
    }
    fun back(): Boolean {
        if (snapshot.countdown != null) { cancelNext(); return true }
        if (!snapshot.visible) return false
        if (snapshot.pages.size > 1) {
            detailJob?.cancel(); mutable.update { it.copy(pages = it.pages.dropLast(1), detailLoading = false, error = null) }
        } else if (!snapshot.menu) openMenu() else mutable.update { it.copy(visible = false) }
        return true
    }
    fun open(item: CatalogTitle) {
        val profile = snapshot.profile ?: return
        mutable.update { it.copy(menu = false, pages = it.pages + CatalogScreenState("detail:${item.id}", item.title, detailId = item.id), error = null, detailLoading = true) }
        detailJob?.cancel()
        detailJob = scope.launch {
            try {
                repository.detail(profile, item.id)
                if (item.kind == CatalogKind.SERIES) {
                    val seasons = repository.seasons(profile, item.id)
                    mutable.update { it.copy(seasons = it.seasons + (item.id to seasons), completeSeasons = it.completeSeasons - seasons.map { s -> s.id }.toSet()) }
                    val saved = snapshot.progressFor(item)?.contentId?.let { id -> snapshot.titles.firstOrNull { it.id == id }?.seasonId }
                    val season = seasons.firstOrNull { it.id == saved } ?: seasons.firstOrNull()
                    if (season != null) loadSeason(item.id, season.id)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (item.kind == CatalogKind.SERIES) {
                    val cached = repository.seasons(profile, item.id, false)
                    mutable.update { it.copy(seasons = it.seasons + (item.id to cached)) }
                    cached.firstOrNull()?.let { season -> mutable.update { it.copy(selectedSeasons = it.selectedSeasons + (item.id to season.id)) } }
                }
                mutable.update { it.copy(error = e.message ?: "Teose laadimine ebaõnnestus") }
            } finally { if (snapshot.profile == profile) mutable.update { it.copy(detailLoading = false) } }
        }
    }
    fun selectSeason(series: String, season: String) { detailJob?.cancel(); detailJob = scope.launch {
        mutable.update { it.copy(detailLoading = true, error = null) }
        try { loadSeason(series, season) } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e.message) }
        finally { mutable.update { it.copy(detailLoading = false) } }
    } }
    private suspend fun loadSeason(series: String, season: String) {
        mutable.update { it.copy(selectedSeasons = it.selectedSeasons + (series to season)) }
        repository.episodes(snapshot.profile ?: return, series, season)
        mutable.update { it.copy(completeSeasons = it.completeSeasons + season) }
    }
    fun all(row: CatalogRow) {
        val old = snapshot.page ?: return
        mutable.update { it.copy(pages = it.pages + CatalogScreenState("${old.key}/${row.key}", row.title, grid = true,
            filter = old.filter.copy(genre = row.key.removePrefix("genre:").takeIf { row.key.startsWith("genre:") }), group = row.key)) }
    }
    fun filter(value: CatalogFilter) { mutable.update { it.copy(pages = it.pages.dropLast(1) + (it.page?.copy(filter = value) ?: return@update it)) } }
    fun query(text: String) {
        searchJob?.cancel(); val generation = ++searchGeneration
        mutable.update { it.copy(query = text.take(100), searching = true) }
        val profile = snapshot.profile ?: return
        searchJob = scope.launch {
            delay(300)
            val ids = withContext(Dispatchers.Default) { repository.search(profile, text) }
            if (generation == searchGeneration && snapshot.profile == profile) mutable.update { it.copy(searchIds = ids, searching = false) }
        }
    }
    fun toggleList(item: CatalogTitle) {
        val profile = snapshot.profile ?: return
        val remove = item.id in snapshot.list
        scope.launch { if (remove) repository.dao.remove(profile, item.id) else repository.dao.add(WatchlistEntity(profile, item.id, System.currentTimeMillis())) }
    }
    fun autoplay() { mutable.update { it.copy(autoplay = !it.autoplay) }; prefs.edit().putBoolean("autoplay", snapshot.autoplay).apply() }
    fun play(item: CatalogTitle, fromBeginning: Boolean = false) {
        cancelNext()
        if (item.kind == CatalogKind.SERIES) {
            seriesPlayJob?.cancel()
            seriesPlayJob = scope.launch {
                val target = continueEpisode(item, fromBeginning) ?: return@launch
                play(target, fromBeginning)
            }
            return
        }
        if (item.right != ViewingRight.AVAILABLE) { fail(when(item.right) { ViewingRight.UNKNOWN -> "Vaatamisõigus on kontrollimata. Värskenda kataloogi."; ViewingRight.EXPIRED -> "See teos ei ole enam saadaval"; else -> "See teos ei kuulu sinu paketti" }); return }
        val progress = snapshot.progress[item.id]
        val position = if (fromBeginning || progress?.watched != false) 0L else progress.positionMs
        mutable.update { it.copy(visible = false, error = null) }; onPlay(item, position)
    }
    private suspend fun continueEpisode(series: CatalogTitle, fromBeginning: Boolean): CatalogTitle? {
        val profile = snapshot.profile ?: return null
        try {
            val seasons = knownSeasons(profile, series.id)
            val last = snapshot.progressFor(series)
            // Load in order until the in-progress or first unwatched episode is known.
            for (season in seasons) {
                val episodes = orderedEpisodes(profile, series.id, season.id)
                if (fromBeginning) episodes.firstOrNull()?.let { return it }
                if (last != null && !last.watched) episodes.firstOrNull { it.id == last.contentId }?.let { return it }
                if (last != null && !last.watched) continue
                episodes.firstOrNull { snapshot.progress[it.id]?.watched != true }?.let { return it }
            }
            fail("Kõik saadaolevad osad on vaadatud. Vali osa ja alusta algusest.")
        } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e.message) }
        return null
    }
    private suspend fun knownSeasons(profile: String, series: String): List<CatalogSeason> =
        snapshot.seasons[series].orEmpty().ifEmpty {
            repository.seasons(profile, series, false).ifEmpty { repository.seasons(profile, series) }
        }
    private suspend fun orderedEpisodes(profile: String, series: String, season: String): List<CatalogTitle> {
        // A history import can contain a single episode from a season. Only a
        // complete episode snapshot can determine the next unwatched episode.
        var episodes = repository.completeEpisodes(profile, series, season)
        mutable.update { it.copy(completeSeasons = it.completeSeasons + season) }
        if (episodes.any { it.right == ViewingRight.UNKNOWN }) {
            repository.refreshRights(profile)
            episodes = repository.cachedEpisodes(profile, series, season)
        }
        return episodes.map { if (it.availableAt()) it else it.copy(right = ViewingRight.EXPIRED) }.sortedBy { it.episode ?: Int.MAX_VALUE }
    }
    fun saveProgress(profile: String, item: CatalogTitle, position: Long, duration: Long) {
        if (position < 0 || duration <= 0) return
        val row = ProgressEntity(profile, item.id, item.seriesId, position.coerceAtMost(duration), duration, System.currentTimeMillis())
        repository.persist(row)
    }
    fun showDetails() { cancelNext(); mutable.update { it.copy(visible = true, menu = false) } }
    fun fail(message: String?) { mutable.update { it.copy(visible = true, error = message ?: "Videot ei saa praegu avada", menu = false) } }
    fun ended(item: CatalogTitle) {
        if (!snapshot.autoplay || item.seriesId == null) return
        nextJob?.cancel()
        nextJob = scope.launch {
            val profile = snapshot.profile ?: return@launch
            try {
                val seasons = knownSeasons(profile, item.seriesId)
                val start = seasons.indexOfFirst { it.id == item.seasonId }
                if (start < 0) return@launch
                for ((index, season) in seasons.withIndex().drop(start)) {
                    val episodes = orderedEpisodes(profile, item.seriesId, season.id)
                    val next = if (index == start) episodes.dropWhile { it.id != item.id }.drop(1).firstOrNull() else episodes.firstOrNull()
                    if (next != null) {
                        if (next.right != ViewingRight.AVAILABLE) return@launch
                        mutable.update { it.copy(next = next, countdown = 10) }
                        countdownJob = scope.launch {
                            for (seconds in 10 downTo 1) { mutable.update { it.copy(countdown = seconds) }; delay(1000) }
                            nextNow()
                        }
                        return@launch
                    }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* No countdown without confirmed next episode. */ }
        }
    }
    fun nextNow() { val next = snapshot.next ?: return; cancelNext(); play(next, true) }
    fun cancelNext() { countdownJob?.cancel(); nextJob?.cancel(); mutable.update { it.copy(countdown = null, next = null) } }
}
