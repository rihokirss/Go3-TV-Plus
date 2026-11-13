package ee.local.go3tvplus.catalog

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CatalogContractTest {
    private fun title(id: String = "1", type: String = "VOD", extra: String = "") = parseCatalogTitle(Json.parseToJsonElement("""{"id":$id,"type":"$type","title":"Öö õhtu","originalTitle":"Original title","active":true$extra}""").jsonObject)!!
    @Test fun capturedMovieAndEpisodeShapesKeepRealMetadataAndOrdering() {
        val movie = title(extra = """, "year":2012,"rating":18,"duration":7127,"genres":[{"name":"Müsteerium"}],"audioLanguages":[{"name":"english"}],"subtitleLanguages":[{"name":"eesti"}]""")
        assertEquals(CatalogKind.MOVIE, movie.kind); assertEquals(18, movie.age); assertEquals(7127L, movie.durationSeconds)
        assertEquals(listOf("eesti"), movie.subtitles); assertEquals(listOf("Müsteerium"), movie.genres)
        val episode = title(type = "EPISODE", extra = ",\"episode\":12")
        assertEquals(12, episode.episode); assertEquals(CatalogKind.EPISODE, episode.kind)
        assertNull(parseCatalogTitle(Json.parseToJsonElement("""{"id":3,"type":"CLIP","title":"Clip"}""").jsonObject))
        assertNull(title().year); assertNull(title().durationSeconds)
    }
    @Test fun compactCachePreservesDisplayFieldsAndDropsUnneededPayloads() {
        val raw = Json.parseToJsonElement("""{"id":1,"type":"VOD","title":"Film","lead":"Description","year":2024,"images":{"3x4":[{"mainUrl":"//static3.go3.tv/poster","miniUrl":"//static3.go3.tv/mini"}]},"gallery":[{"huge":"unused"}],"trailer":true,"genres":[{"id":1,"name":"Draama"}]}""").jsonObject
        val compact = compactCatalogJson(raw)
        assertEquals(parseCatalogTitle(raw), parseCatalogTitle(compact))
        assertFalse(compact.containsKey("gallery")); assertFalse(compact.containsKey("trailer"))
    }
    @Test fun payableAndPresenceNeverImplyEntitlement() {
        val payable = title(extra = ",\"payable\":true")
        val free = title(extra = ",\"payable\":false")
        for (item in listOf(payable, free)) {
            assertEquals(ViewingRight.UNKNOWN, resolveViewingRight(item, null))
            assertEquals(ViewingRight.LOCKED, resolveViewingRight(item, emptyMap()))
            assertEquals(ViewingRight.AVAILABLE, resolveViewingRight(item, mapOf(item.id to null)))
            assertEquals(ViewingRight.EXPIRED, resolveViewingRight(item, mapOf(item.id to 999L), 1000))
        }
    }
    @Test fun expiredInactiveAndFutureTitlesCannotPlayEvenWithRights() {
        for (item in listOf(title(extra = ",\"till\":\"2000-01-01T00:00:00Z\""), title(extra = ",\"since\":\"2099-01-01T00:00:00Z\""), title().copy(active = false))) {
            assertEquals(ViewingRight.EXPIRED, resolveViewingRight(item, mapOf(item.id to null)))
        }
    }
    @Test fun searchIsAccentInsensitiveAndFtsSyntaxCannotEscapeItsTokens() {
        assertEquals("oo ohtu", normalizeCatalogText("Öö ÕHTU"))
        assertEquals("\"oo*\" \"ohtu*\"", catalogSearchQuery("öö õhtu"))
        assertEquals("\"or*\" \"drop*\"", catalogSearchQuery("\" OR * DROP"))
        assertNull(catalogSearchQuery("  *\" "))
        assertTrue(title().searchText.contains("original title"))
    }
    @Test fun watchedThresholdIsPreciselyNinetyFivePercent() {
        val progress = CatalogProgress("a", "film", null, 9499, 10000, 0)
        assertFalse(progress.watched); assertTrue(progress.copy(positionMs = 9500).watched)
        assertFalse(progress.copy(durationMs = 0).watched)
        assertEquals(1f, progress.copy(positionMs = 20000).fraction)
    }
    @Test fun ownPackageFilteringAndPerSeriesContinueRowsUseRealProgress() {
        val series = title("2", "SERIAL").copy(right = ViewingRight.AVAILABLE)
        val film = title().copy(right = ViewingRight.AVAILABLE)
        val locked = title("3").copy(right = ViewingRight.LOCKED)
        val state = CatalogUiState(titles = listOf(film, series, locked), list = listOf("3"), progress = mapOf(
            "e1" to CatalogProgress("profile", "e1", "2", 100, 100, 1), "e2" to CatalogProgress("profile", "e2", "2", 20, 100, 2)))
        assertEquals(setOf("1", "2"), state.filtered().map { it.id }.toSet())
        assertEquals(3, state.filtered(CatalogFilter(allRights = true)).size)
        assertEquals(listOf("2"), state.rows().first { it.key == "continue" }.items.map { it.id })
        assertEquals("e2", state.progressFor(series)?.contentId)
        assertFalse(state.rows().any { it.key == "list" })
    }
    @Test fun completedSeriesLeavesContinueOnlyWhenEverySeasonIsKnownAndWatched() {
        val series = title("2", "SERIAL").copy(right = ViewingRight.AVAILABLE)
        val episode = title("4", "EPISODE").copy(seriesId = "2", seasonId = "season1")
        val state = CatalogUiState(titles = listOf(series, episode), completeSeasons = setOf("season1"),
            seasons = mapOf("2" to listOf(CatalogSeason("season1", "2", 1, "First"))),
            progress = mapOf("4" to CatalogProgress("profile", "4", "2", 100, 100, 1)))
        assertFalse(state.rows().any { it.key == "continue" })
        assertTrue(state.copy(seasons = emptyMap()).rows().any { it.key == "continue" })
        assertTrue(state.copy(seasons = mapOf("2" to state.seasons.getValue("2") + CatalogSeason("season2", "2", 2, "Second"))).rows().any { it.key == "continue" })
    }

    @Test fun genresContainUnopenedMoviesAndRespectKindRightsAndYearFilters() {
        val first = title("1", extra = """, "year":2024,"genres":[{"name":"Draama"},{"name":"Märul"}]""").copy(right = ViewingRight.AVAILABLE)
        val second = title("2", extra = """, "year":2024,"genres":[{"name":"Draama"}]""").copy(right = ViewingRight.AVAILABLE)
        val locked = first.copy(id = "locked", right = ViewingRight.LOCKED)
        val series = second.copy(id = "series", kind = CatalogKind.SERIES)
        val state = CatalogUiState(titles = listOf(first, second, locked, series), section = CatalogSection.MOVIES,
            pages = listOf(CatalogScreenState("movies", "Filmid", filter = CatalogFilter(kind = CatalogKind.MOVIE))))
        assertEquals(listOf("1", "2"), state.rows().single { it.key == "genre:Draama" }.items.map { it.id })
        assertEquals(listOf("1"), state.rows().single { it.key == "genre:Märul" }.items.map { it.id })
        assertEquals(listOf("1", "2"), state.filtered(CatalogFilter(kind = CatalogKind.MOVIE, genre = "Draama", year = 2024)).map { it.id })
        assertTrue(state.filtered(CatalogFilter(genre = "Draama", year = 2023)).isEmpty())
    }

    @Test fun popularityUsesServerOrderAndNeverFallsBackToRecentlyAddedOrSeries() {
        val a = title("a").copy(right = ViewingRight.AVAILABLE, added = 300)
        val b = a.copy(id = "b", added = 100)
        val locked = a.copy(id = "locked", right = ViewingRight.LOCKED)
        val series = a.copy(id = "series", kind = CatalogKind.SERIES)
        val state = CatalogUiState(titles = listOf(a, b, locked, series), popularMovieIds = listOf("b", "locked", "missing", "series", "a", "b"))
        assertEquals(listOf("b", "a"), state.rows().single { it.key == "popular" }.items.map { it.id })
        assertEquals(listOf("b", "locked", "a"), state.popularMovies(state.filtered(CatalogFilter(allRights = true))).map { it.id })
        val grid = state.copy(pages = listOf(CatalogScreenState("popular", "Populaarsed", grid = true, group = "popular")))
        assertEquals(listOf("b", "a"), grid.browseItems().map { it.id })
        assertFalse(state.copy(popularMovieIds = emptyList()).rows().any { it.key == "popular" })
        assertFalse(state.copy(pages = listOf(CatalogScreenState("series", "Seriaalid", filter = CatalogFilter(kind = CatalogKind.SERIES)))).rows().any { it.key == "popular" })
    }

}
