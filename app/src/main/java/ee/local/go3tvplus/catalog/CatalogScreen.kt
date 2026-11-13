@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package ee.local.go3tvplus.catalog

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ee.local.go3tvplus.ui.NavigationRail
import ee.local.go3tvplus.ui.NavigationStyle

private val Ink = NavigationStyle.Background
private val Muted = NavigationStyle.Muted
private val Accent = NavigationStyle.Accent
private val Shape = NavigationStyle.Shape
private val LocalContentAutofocus = staticCompositionLocalOf { true }
private val LocalBrowseRestoreTarget = staticCompositionLocalOf<String?> { null }

private class CatalogContentFocus {
    var request: (() -> Boolean)? = null
}

@Composable
fun CatalogOverlay(model: CatalogViewModel) {
    val state by model.state.collectAsState()
    val saved = rememberSaveableStateHolderCompat()
    BackHandler(state.visible) { model.back() }
    if (state.visible) {
        val menuFocus = remember { FocusRequester() }
        val contentFocus = remember { CatalogContentFocus() }
        var hero by remember { mutableStateOf<CatalogTitle?>(null) }
        var background by remember { mutableStateOf<CatalogTitle?>(null) }
        LaunchedEffect(hero?.id) { delay(140); background = hero }
        val page = state.page
        val isTvMenu = page == null
        Box(Modifier.fillMaxSize().onPreviewKeyEvent {
            val event = it.nativeKeyEvent
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)) model.recordRemoteInput()
            false
        }.then(if (isTvMenu) Modifier else Modifier.background(Ink))) {
            if (!isTvMenu) {
                Crossfade(background, animationSpec = tween(350), label = "catalog backdrop") { item ->
                    Box(Modifier.fillMaxSize()) {
                        AsyncImage(item?.backdrop, null, Modifier.fillMaxWidth().height(440.dp), contentScale = ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Ink, Ink.copy(alpha = .7f), Ink.copy(alpha = .1f)))))
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink.copy(alpha = .1f), Ink.copy(alpha = .4f), Ink), endY = 800f)))
                    }
                }
            }
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    CatalogSection.entries.forEach { section ->
                        val selected = state.section == section
                        Button(onClick = { model.select(section) },
                            modifier = Modifier.testTag("catalog-menu:${section.name}").fillMaxWidth().focusProperties { canFocus = !state.contentFocusPending || state.menu }
                                .onFocusChanged { if (it.isFocused) model.menuFocused() }.then(if (selected || (isTvMenu && section == CatalogSection.TV)) Modifier.focusRequester(menuFocus) else Modifier)
                                .onPreviewKeyEvent {
                                    val event = it.nativeKeyEvent
                                    if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && page != null) {
                                        if (contentFocus.request?.invoke() == true) model.contentFocus()
                                        true
                                    } else false
                                },
                            colors = ButtonDefaults.colors(containerColor = if (selected) Color.White.copy(alpha = .1f) else Color.Transparent,
                                focusedContainerColor = Color.White, focusedContentColor = Ink, contentColor = if (selected) Color.White else Muted),
                            contentPadding = PaddingValues(horizontal = 15.dp, vertical = 10.dp),
                            scale = ButtonDefaults.scale(focusedScale = 1f)) {
                            Text(section.label, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
                if (page != null) saved.SaveableStateProvider(page.key) {
                    CompositionLocalProvider(LocalContentAutofocus provides !state.menu) {
                    Column(Modifier.weight(1f).fillMaxHeight().focusGroup()) {
                        if (page.detailId != null) DetailContent(model, state, menuFocus, contentFocus) { hero = it }
                        else BrowseContent(model, state, menuFocus, contentFocus) { hero = it }
                    }
                    }
                }
            }
        }
        LaunchedEffect(state.menu, isTvMenu) { if (state.menu || isTvMenu) { delay(16); menuFocus.requestFocus() } }
    }
    if (state.countdown != null) {
        Box(Modifier.fillMaxSize().padding(36.dp), contentAlignment = Alignment.BottomEnd) {
            Column(Modifier.width(340.dp).background(Ink.copy(alpha = .96f), Shape).border(1.dp, Color.White.copy(alpha = .3f), Shape).padding(22.dp)) {
                Text("Järgmine osa ${state.countdown} sekundi pärast", color = Color.White, fontSize = 20.sp)
                Text(state.next?.title.orEmpty(), color = Muted, fontSize = 15.sp, modifier = Modifier.padding(vertical = 10.dp))
                Text("OK · vaata kohe     BACK · tühista", color = Accent, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun rememberSaveableStateHolderCompat() = androidx.compose.runtime.saveable.rememberSaveableStateHolder()

@Composable
private fun BrowseContent(model: CatalogViewModel, state: CatalogUiState, menu: FocusRequester, contentFocus: CatalogContentFocus, focus: (CatalogTitle) -> Unit) {
    val page = state.page ?: return
    val focusTargets = remember(page.key) { mutableStateMapOf<String, FocusRequester>() }
    val loadedRows by produceState<List<CatalogRow>?>(null, state.titles, state.progress, state.list, state.popularMovieIds, page.filter, state.section, page.grid) {
        value = if (page.grid) emptyList() else withContext(Dispatchers.Default) { state.rows() }
    }
    val loadedItems by produceState<List<CatalogTitle>?>(null, state.titles, state.list, state.popularMovieIds, state.searchIds, state.searchTitles, page.filter, page.group, state.progress) {
        value = withContext(Dispatchers.Default) { state.browseItems() }
    }
    val rows = loadedRows.orEmpty()
    val filtered = loadedItems.orEmpty()
    val ready = loadedItems != null && (page.grid || loadedRows != null)
    val remembered = model.focusedKeys[page.key]
    val rememberedRow = rows.indexOfFirst { row ->
        remembered == "${row.key}:all" || row.items.take(24).any { remembered == "${row.key}:${it.id}" }
    }
    val rememberedGrid = filtered.indexOfFirst { remembered == "grid:${it.id}" }
    // A remembered card can exist outside the composed viewport. Keep waiting
    // for that stable ID instead of replacing it with the first visible card.
    val restoreKey = remembered?.takeIf {
        !ready || it == "search" || it.startsWith("filter:") ||
            (if (page.grid) rememberedGrid >= 0 else rememberedRow >= 0)
    }
    val initialFocusKey = when {
        page.key == CatalogSection.SEARCH.name -> "search"
        page.grid -> filtered.getOrNull((model.scrollPositions[page.key + ":grid"]?.first ?: 0).coerceAtMost(filtered.lastIndex).coerceAtLeast(0))?.let { "grid:${it.id}" }
        else -> rows.getOrNull((model.scrollPositions[page.key + ":rows"]?.first ?: 0).coerceAtMost(rows.lastIndex).coerceAtLeast(0))?.let { row ->
            val index = (model.scrollPositions[page.key + ":" + row.key]?.first ?: 0).coerceAtMost(minOf(row.items.size, 24))
            row.items.take(24).getOrNull(index)?.let { "${row.key}:${it.id}" } ?: "${row.key}:all"
        }
    }
    BindContentFocus(model, page.key, focusTargets, contentFocus, restoreKey ?: initialFocusKey)
    val availableFocusTargets = focusTargets.toMap()
    LaunchedEffect(state.contentFocusPending, state.loading, state.searching, rows, filtered, availableFocusTargets, initialFocusKey, restoreKey, ready) {
        if (state.contentFocusPending && !state.menu && ready) {
            delay(30)
            // Wait for the destination's asynchronous rows/grid, not the rail's
            // automatic fallback. An empty catalogue can still focus its filters.
            val empty = withContext(Dispatchers.Default) { if (page.grid) state.browseItems().isEmpty() else state.rows().isEmpty() }
            val target = preferredContentFocus(model, page.key, availableFocusTargets, empty && !state.loading && !state.searching, restoreKey ?: initialFocusKey)
            withContext(Dispatchers.Main.immediate) {
                if (model.state.value.page?.key == page.key && model.state.value.contentFocusPending && !model.state.value.menu) target?.let { requestCatalogFocus(model, page.key, it.first, it.second) }
            }
        }
    }
    var focused by remember { mutableStateOf<CatalogTitle?>(null) }
    var focusedRow by remember { mutableStateOf<String?>(null) }
    val active = focused ?: filtered.firstOrNull { remembered == "grid:${it.id}" || remembered?.endsWith(":${it.id}") == true } ?: filtered.firstOrNull()
    LaunchedEffect(active?.id) { active?.let(focus) }
    CompositionLocalProvider(LocalBrowseRestoreTarget provides (restoreKey ?: initialFocusKey).takeIf { state.contentFocusPending && !state.menu }) {
    Column(Modifier.fillMaxSize()) {
        if (page.grid) {
            Column(Modifier.padding(horizontal = 32.dp, vertical = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(page.title, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    if (state.isSearchPage) Text(if (state.searching) "Otsin…" else if (filtered.size == 1) "1 tulemus" else "${filtered.size} tulemust", color = Muted, fontSize = 12.sp, modifier = Modifier.testTag("catalog-search-count"))
                }
                if (state.isSearchPage) SearchField(state.query, model::query, focusTargets) { model.recordFocus(page.key, "search") }
                Filters(model, state, menu, focusTargets)
                Status(state, model)
            }
            if (ready) {
                val saved = model.scrollPositions[page.key + ":grid"]
                val scroll = remember(page.key) { LazyGridState(saved?.first ?: 0, saved?.second ?: 0) }
                LaunchedEffect(scroll) { snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { if (model.state.value.page?.key == page.key && !model.state.value.contentFocusPending) model.scrollPositions[page.key + ":grid"] = it } }
                LaunchedEffect(state.contentFocusPending, rememberedGrid) {
                    if (state.contentFocusPending && rememberedGrid >= 0 && scroll.layoutInfo.visibleItemsInfo.none { it.index == rememberedGrid }) scroll.scrollToItem(rememberedGrid)
                }
                val upcoming by remember(scroll, filtered) { derivedStateOf {
                    val last = scroll.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
                    if (last < 0) emptyList() else filtered.drop(last + 1).take(2)
                } }
                PrefetchCatalogPosters(upcoming, model)
                LazyVerticalGrid(GridCells.Fixed(5), state = scroll, contentPadding = PaddingValues(start = 32.dp, end = 32.dp, bottom = 30.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.weight(1f)) {
                    itemsIndexed(filtered, key = { _, it -> it.id }) { index, item ->
                        Poster(model, page.key, "grid:${item.id}", item, state.progressFor(item), index % 5 == 0, menu, focusTargets,
                            onFocus = { focused = item }, onClick = {
                                model.recordFocus(page.key, "grid:${item.id}")
                                model.scrollPositions[page.key + ":grid"] = scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset
                                model.open(item)
                            })
                    }
                    if (filtered.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyCatalog(state) }
                }
            }
        } else if (ready) {
            val saved = model.scrollPositions[page.key + ":rows"]
            val scroll = remember(page.key) { LazyListState(saved?.first ?: 0, saved?.second ?: 0) }
            LaunchedEffect(scroll) { snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { if (model.state.value.page?.key == page.key && !model.state.value.contentFocusPending) model.scrollPositions[page.key + ":rows"] = it } }
            LaunchedEffect(state.contentFocusPending, rememberedRow) {
                if (state.contentFocusPending && rememberedRow >= 0 && scroll.layoutInfo.visibleItemsInfo.none { it.index == rememberedRow }) scroll.scrollToItem(rememberedRow)
            }
            Column(Modifier.fillMaxWidth().heightIn(min = 150.dp).padding(start = 32.dp, end = 105.dp, top = 25.dp, bottom = 8.dp)) {
                Text(page.title.uppercase(), color = Accent, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(9.dp))
                Text(active?.title ?: page.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(active?.let(::metadata).orEmpty(), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 7.dp))
                Text(active?.description.orEmpty(), color = Color.White.copy(alpha = .8f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
            }
            Column(Modifier.padding(horizontal = 32.dp)) { Status(state, model); Filters(model, state, menu, focusTargets) }
            LazyColumn(state = scroll, contentPadding = PaddingValues(top = 12.dp, bottom = 25.dp), verticalArrangement = Arrangement.spacedBy(19.dp), modifier = Modifier.weight(1f)) {
                items(rows, key = { it.key }) { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(row.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 32.dp))
                        val rowKey = page.key + ":" + row.key
                        val position = model.scrollPositions[rowKey]
                        val rowScroll = remember(rowKey) { LazyListState(position?.first ?: 0, position?.second ?: 0) }
                        LaunchedEffect(rowScroll) { snapshotFlow { rowScroll.firstVisibleItemIndex to rowScroll.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { if (model.state.value.page?.key == page.key && !model.state.value.contentFocusPending) model.scrollPositions[rowKey] = it } }
                        val restoreIndex = if (restoreKey == "${row.key}:all") minOf(row.items.size, 24)
                            else row.items.take(24).indexOfFirst { restoreKey == "${row.key}:${it.id}" }
                        LaunchedEffect(state.contentFocusPending, restoreIndex) {
                            if (state.contentFocusPending && restoreIndex >= 0 && rowScroll.layoutInfo.visibleItemsInfo.none { it.index == restoreIndex }) rowScroll.scrollToItem(restoreIndex)
                        }
                        fun savePosition() {
                            model.scrollPositions[page.key + ":rows"] = scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset
                            model.scrollPositions[rowKey] = rowScroll.firstVisibleItemIndex to rowScroll.firstVisibleItemScrollOffset
                        }
                        if (focusedRow == row.key || (focusedRow == null && row.key == rows.firstOrNull()?.key)) {
                            val upcoming by remember(rowScroll, row.items) { derivedStateOf {
                                val last = rowScroll.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
                                if (last < 0) emptyList() else row.items.take(24).drop(last + 1).take(2)
                            } }
                            PrefetchCatalogPosters(upcoming, model)
                        }
                        LazyRow(state = rowScroll, contentPadding = PaddingValues(horizontal = 32.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            itemsIndexed(row.items.take(24), key = { _, item -> item.id }) { index, item ->
                                Poster(model, page.key, "${row.key}:${item.id}", item, state.progressFor(item), index == 0, menu, focusTargets,
                                    onFocus = { focused = item; focusedRow = row.key }, onClick = {
                                        model.recordFocus(page.key, "${row.key}:${item.id}")
                                        savePosition()
                                        model.open(item)
                                    })
                            }
                            item(key = "all") {
                                Button(onClick = { model.recordFocus(page.key, "${row.key}:all"); savePosition(); model.all(row) }, modifier = restoredCatalogFocus(model, page.key, "${row.key}:all", targets = focusTargets).width(116.dp).height(157.dp),
                                    shape = ButtonDefaults.shape(shape = Shape), scale = ButtonDefaults.scale(focusedScale = 1f),
                                    colors = ButtonDefaults.colors(containerColor = Color.White.copy(alpha = .09f), contentColor = Muted, focusedContainerColor = Color.White, focusedContentColor = Ink)) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Vaata kõiki", fontSize = 15.sp); Text("${row.items.size} teost", fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }
                if (rows.isEmpty()) item { Box(Modifier.padding(horizontal = 32.dp)) { EmptyCatalog(state) } }
            }
        }
    }
    }
}

@Composable
private fun SearchField(query: String, change: (String) -> Unit, targets: MutableMap<String, FocusRequester>, focused: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val restoring = LocalBrowseRestoreTarget.current
    RegisterCatalogFocus(targets, "search", requester)
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
    BasicTextField(query, change, singleLine = true, cursorBrush = SolidColor(Accent), textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 18.sp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.moveFocus(FocusDirection.Down) }),
        modifier = Modifier.testTag("catalog-search-input").fillMaxWidth().padding(top = 12.dp).background(Color.White.copy(alpha = .09f), Shape).border(1.dp, Color.White.copy(alpha = .25f), Shape)
            .focusProperties { canFocus = restoring == null || restoring == "search" }.focusRequester(requester).onFocusChanged { if (it.isFocused) { focused(); keyboard?.show() } }.padding(14.dp),
        decorationBox = { inner -> Box { if (query.isEmpty()) Text("Otsi pealkirja või algpealkirja…", color = Muted, fontSize = 17.sp); inner() } })
}

@Composable
private fun Filters(model: CatalogViewModel, state: CatalogUiState, menu: FocusRequester, focusTargets: MutableMap<String, FocusRequester>) {
    val filter = state.page?.filter ?: return
    val page = state.page ?: return
    var choosing by remember { mutableStateOf<String?>(null) }
    val choices by produceState<List<CatalogTitle>>(emptyList(), state.titles, state.list, state.section, state.searchIds, state.searchTitles, state.popularMovieIds, state.page?.group, filter, state.progress) {
        value = withContext(Dispatchers.Default) { state.browseItems(filter.copy(genre = null, year = null)) }
    }
    val genres = remember(choices) { choices.flatMap { it.genres }.distinct().sorted() }
    val years = remember(choices) { choices.mapNotNull { it.year }.distinct().sortedDescending() }
    @Composable fun control(key: String, label: String, click: () -> Unit) {
        FilterButton(label, click, restoredCatalogFocus(model, page.key, "filter:$key", targets = focusTargets)
            .onPreviewKeyEvent {
                if (key == "rights" && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    model.openMenu(); menu.requestFocus(); true
                } else false
            })
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
        item { control("rights", if (filter.allRights) "Kogu kataloog" else "Minu pakett") { model.filter(filter.copy(allRights = !filter.allRights)) } }
        if (state.page?.grid == true) item { control("kind", when(filter.kind) { CatalogKind.MOVIE -> "Filmid"; CatalogKind.SERIES -> "Seriaalid"; else -> "Filmid ja seriaalid" }) { choosing = "kind" } }
        item { control("genre", filter.genre ?: "Kõik žanrid") { choosing = "genre" } }
        item { control("year", filter.year?.toString() ?: "Kõik aastad") { choosing = "year" } }
        if (state.page?.group != "popular") item { control("sort", filter.sort.label) { choosing = "sort" } }
    }
    if (choosing != null) {
        val options: List<Pair<String, () -> Unit>> = when(choosing) {
            "genre" -> listOf("Kõik žanrid" to { model.filter(filter.copy(genre = null)) }) + genres.map { genre -> genre to { model.filter(filter.copy(genre = genre)) } }
            "year" -> listOf("Kõik aastad" to { model.filter(filter.copy(year = null)) }) + years.map { year -> year.toString() to { model.filter(filter.copy(year = year)) } }
            "sort" -> CatalogSort.entries.map { sort -> sort.label to { model.filter(filter.copy(sort = sort)) } }
            else -> listOf("Filmid ja seriaalid" to { model.filter(filter.copy(kind = null)) }, "Filmid" to { model.filter(filter.copy(kind = CatalogKind.MOVIE)) }, "Seriaalid" to { model.filter(filter.copy(kind = CatalogKind.SERIES)) })
        }
        Dialog(onDismissRequest = { choosing = null }) {
            val first = remember { FocusRequester() }
            LazyColumn(Modifier.width(380.dp).heightIn(max = 400.dp).background(Ink, Shape).border(1.dp, Muted, Shape).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(options) { index, option -> Button(onClick = { option.second(); choosing = null }, modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(first) else Modifier)) { Text(option.first) } }
            }
            LaunchedEffect(choosing) { first.requestFocus() }
        }
    }
}
@Composable private fun FilterButton(label: String, click: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = click, modifier = modifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), scale = ButtonDefaults.scale(focusedScale = 1f), colors = ButtonDefaults.colors(containerColor = Color.White.copy(alpha = .09f), contentColor = Muted)) { Text(label, fontSize = 11.sp) }
}
@Composable private fun Status(state: CatalogUiState, model: CatalogViewModel) {
    if (state.error != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        Text(state.error, color = Color(0xFFFFC9AD), fontSize = 12.sp, modifier = Modifier.weight(1f))
        FilterButton("Proovi uuesti", model::refresh)
    } else if (state.history.error != null) Text(state.history.error, color = Muted, fontSize = 11.sp)
    else if (state.isSearchPage && state.searchOffline) Text("Võrguotsing pole praegu saadaval. Näitan salvestatud tulemusi.", color = Muted, fontSize = 11.sp)
    else if (state.loading || (state.searching && !state.isSearchPage)) Text(if (state.searching && !state.isSearchPage) "Otsin…" else "Värskendan valikut…", color = Muted, fontSize = 11.sp)
}
@Composable private fun EmptyCatalog(state: CatalogUiState) {
    Text(when { state.error != null -> "Salvestatud valik on siin ka ühenduse taastumiseni."; state.loading -> "Laadin esimest valikut…"; state.section == CatalogSection.LIST -> "Lisa lemmikud teose detailvaates oma nimekirja."; state.section == CatalogSection.SEARCH -> "Selle otsingu ja filtritega teoseid ei leitud."; else -> "Selle filtriga teoseid ei leitud. Proovi kogu kataloogi või värskenda valikut." }, color = Muted, fontSize = 16.sp, modifier = Modifier.padding(vertical = 30.dp))
}

@Composable
private fun Poster(model: CatalogViewModel, page: String, key: String, item: CatalogTitle, progress: CatalogProgress?, first: Boolean, menu: FocusRequester, targets: MutableMap<String, FocusRequester>, onFocus: () -> Unit, onClick: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val width = with(density) { 116.dp.roundToPx() }
    val height = with(density) { 157.dp.roundToPx() }
    val image = remember(item.poster, width, height) { catalogPosterRequest(context, item.poster, width, height) }
    val requester = remember { FocusRequester() }
    RegisterCatalogFocus(targets, key, requester)
    var focused by remember { mutableStateOf(false) }
    val restoring = LocalBrowseRestoreTarget.current
    Column(Modifier.testTag("catalog-poster:$page:$key").width(116.dp).focusProperties { canFocus = restoring == null || restoring == key }.focusRequester(requester).onFocusChanged {
        focused = it.isFocused
        if (it.isFocused) { model.recordFocus(page, key); onFocus() }
    }.onPreviewKeyEvent {
        if (first && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { model.openMenu(); menu.requestFocus(); true } else false
    }.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(116.dp).height(157.dp).clip(Shape).background(Color(0xFF202839))) {
            Text(item.title, color = Muted, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center).padding(12.dp), maxLines = 5)
            AsyncImage(image, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (item.right != ViewingRight.AVAILABLE) Text(when(item.right) { ViewingRight.LOCKED -> "🔒"; ViewingRight.EXPIRED -> "AEGUNUD"; else -> "?" }, color = Color.White, fontSize = 14.sp, modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).background(Ink.copy(alpha = .8f), RoundedCornerShape(4.dp)).padding(5.dp))
            if (progress != null) ProgressBar(progress.fraction, Modifier.align(Alignment.BottomStart))
            if (focused) {
                // Dark separation on both sides keeps the white outline visible
                // against white artwork as well as the dark page background.
                Box(Modifier.matchParentSize().border(6.dp, Ink, Shape))
                Box(Modifier.matchParentSize().padding(2.dp).border(2.dp, Color.White, RoundedCornerShape(8.dp)))
            }
        }
        Text(item.title, color = if (focused) Color.White else Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
    }
}
@Composable private fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .25f))) { Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(Accent)) }
}
private fun metadata(item: CatalogTitle): String = listOfNotNull(item.year?.toString(), item.age?.let { "$it+" }, item.durationSeconds?.takeIf { it > 0 }?.let { "${it / 60} min" }, item.genres.firstOrNull()).joinToString("   ·   ")

@Composable
private fun DetailContent(model: CatalogViewModel, state: CatalogUiState, menu: FocusRequester, contentFocus: CatalogContentFocus, focus: (CatalogTitle) -> Unit) {
    val item = state.detail ?: return
    val page = state.page ?: return
    val focusTargets = remember(page.key) { mutableMapOf<String, FocusRequester>() }
    BindContentFocus(model, page.key, focusTargets, contentFocus)
    val position = model.scrollPositions[page.key]
    val scroll = rememberLazyListState(position?.first ?: 0, position?.second ?: 0)
    LaunchedEffect(scroll) { snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { model.scrollPositions[page.key] = it } }
    val progress = state.progressFor(item)
    val episodes = remember(state.titles, state.selectedSeasons, item.id) { state.titles.filter { it.seriesId == item.id && it.seasonId == state.selectedSeasons[item.id] }.sortedBy { it.episode ?: Int.MAX_VALUE } }
    LaunchedEffect(item.id) { focus(item) }
    LazyColumn(state = scroll, contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 35.dp, bottom = 35.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(if (item.kind == CatalogKind.SERIES) "SERIAAL" else "FILM", color = Accent, fontSize = 11.sp, letterSpacing = 2.sp)
            Text(item.title, color = Color.White, fontSize = 35.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(metadata(item), color = Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp))
            Text(item.description.orEmpty(), color = Color.White.copy(alpha = .85f), fontSize = 14.sp, lineHeight = 21.sp, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(.92f))
            if (item.audio.isNotEmpty() || item.subtitles.isNotEmpty()) Text(listOfNotNull(item.audio.takeIf { it.isNotEmpty() }?.joinToString(", ", "Heli: "), item.subtitles.takeIf { it.isNotEmpty() }?.joinToString(", ", "Subtiitrid: ")).joinToString("  ·  "), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            if (item.right != ViewingRight.AVAILABLE) Text(when(item.right) { ViewingRight.LOCKED -> "🔒 See teos ei kuulu sinu paketti"; ViewingRight.UNKNOWN -> "Vaatamisõigus on kontrollimata"; ViewingRight.EXPIRED -> "See teos ei ole praegu saadaval"; else -> "" }, color = Color(0xFFFFC9AD), fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                item { Button(onClick = { model.play(item) }, modifier = restoredCatalogFocus(model, page.key, "play", initial = true, targets = focusTargets).onPreviewKeyEvent { if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { model.openMenu(); menu.requestFocus(); true } else false }) { Text(if (progress != null && !progress.watched && progress.positionMs > 0) "Jätka" else "Vaata") } }
                item { Button(onClick = { model.play(item, true) }, modifier = restoredCatalogFocus(model, page.key, "restart", targets = focusTargets)) { Text("Alusta algusest") } }
                item { Button(onClick = { model.toggleList(item) }, modifier = restoredCatalogFocus(model, page.key, "list", targets = focusTargets)) { Text(if (item.id in state.list) "Eemalda nimekirjast" else "+ Minu nimekiri") } }
            }
        }
        item { Status(state, model) }
        if (item.kind == CatalogKind.SERIES) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(state.seasons[item.id].orEmpty(), key = { it.id }) { season ->
                        Button(onClick = { model.selectSeason(item.id, season.id) }, modifier = restoredCatalogFocus(model, page.key, "season:${season.id}", targets = focusTargets), colors = ButtonDefaults.colors(containerColor = if (state.selectedSeasons[item.id] == season.id) Color.White.copy(alpha = .2f) else Color.White.copy(alpha = .07f))) { Text(if (season.number > 0) "${season.number}. hooaeg" else season.title) }
                    }
                }
            }
            items(episodes, key = { it.id }) { episode ->
                val episodeFocus = remember { FocusRequester() }
                RegisterCatalogFocus(focusTargets, episode.id, episodeFocus)
                LaunchedEffect(Unit) { if (!state.menu && model.focusedKeys[page.key] == episode.id) { delay(30); if (model.state.value.contentFocusPending) requestCatalogFocus(model, page.key, episode.id, episodeFocus) } }
                val p = state.progress[episode.id]
                Button(onClick = { model.play(episode) }, modifier = Modifier.fillMaxWidth().focusRequester(episodeFocus).onFocusChanged { if (it.isFocused) model.recordFocus(page.key, episode.id) }, scale = ButtonDefaults.scale(focusedScale = 1.01f), contentPadding = PaddingValues(0.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(148.dp).height(87.dp).background(Color(0xFF202839))) {
                            AsyncImage(episode.backdrop, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            if (p != null) ProgressBar(p.fraction, Modifier.align(Alignment.BottomStart))
                        }
                        Column(Modifier.weight(1f).padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(listOfNotNull(episode.episode?.toString(), episode.title).joinToString(". "), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(listOfNotNull(episode.durationSeconds?.let { "${it / 60} min" }, if (p?.watched == true) "Vaadatud" else null,
                                when(episode.right) { ViewingRight.LOCKED -> "🔒"; ViewingRight.UNKNOWN -> "Õigus kontrollimata"; ViewingRight.EXPIRED -> "Aegunud"; else -> null }).joinToString(" · "), fontSize = 11.sp)
                            Text(episode.description.orEmpty(), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Button(onClick = { model.play(episode, true) }, modifier = restoredCatalogFocus(model, page.key, "restart:${episode.id}", targets = focusTargets).padding(end = 12.dp)) { Text("Algusest", fontSize = 11.sp) }
                    }
                }
            }
            if (episodes.isEmpty()) item { Text(if (state.detailLoading) "Laadin osasid…" else "Selle hooaja osad ei ole praegu saadaval", color = Muted, fontSize = 15.sp) }
        }
    }
}

@Composable
private fun restoredCatalogFocus(model: CatalogViewModel, page: String, key: String, initial: Boolean = false, targets: MutableMap<String, FocusRequester>? = null): Modifier {
    val requester = remember { FocusRequester() }
    RegisterCatalogFocus(targets, key, requester)
    val allowAutofocus by rememberUpdatedState(LocalContentAutofocus.current)
    LaunchedEffect(Unit) {
        val previous = model.focusedKeys[page]
        if (allowAutofocus && (previous == key || (previous == null && initial))) { delay(30); if (allowAutofocus && model.state.value.contentFocusPending) requestCatalogFocus(model, page, key, requester) }
    }
    val restoring = LocalBrowseRestoreTarget.current
    return Modifier.focusProperties { canFocus = restoring == null || restoring == key }.focusRequester(requester).onFocusChanged { if (it.isFocused) model.recordFocus(page, key) }
}

@Composable
private fun RegisterCatalogFocus(targets: MutableMap<String, FocusRequester>?, key: String, requester: FocusRequester) {
    DisposableEffect(targets, key, requester) {
        targets?.set(key, requester)
        onDispose { if (targets?.get(key) === requester) targets.remove(key) }
    }
}

@Composable
private fun BindContentFocus(model: CatalogViewModel, page: String, targets: Map<String, FocusRequester>, contentFocus: CatalogContentFocus, initialKey: String? = null) {
    DisposableEffect(page, targets, contentFocus, initialKey) {
        // Resolve the actual remembered control before transferring focus from
        // the rail. Entering the whole group first briefly focuses its first filter.
        val request = {
            preferredContentFocus(model, page, targets, fallbackToFilters = true, initialKey = initialKey)?.let { requestCatalogFocus(model, page, it.first, it.second) } == true
        }
        contentFocus.request = request
        onDispose { if (contentFocus.request === request) contentFocus.request = null }
    }
}

private fun preferredContentFocus(model: CatalogViewModel, page: String, targets: Map<String, FocusRequester>, fallbackToFilters: Boolean, initialKey: String? = null): Pair<String, FocusRequester>? {
    targets[model.focusedKeys[page]]?.let { return model.focusedKeys.getValue(page) to it }
    if (initialKey != null) return targets[initialKey]?.let { initialKey to it }
    // SnapshotStateMap does not retain visual/insertion order. The editable
    // search field must win over whichever poster happens to be registered first.
    if (page == CatalogSection.SEARCH.name) return targets["search"]?.let { "search" to it }
    return targets.entries.firstOrNull { !it.key.startsWith("filter:") }?.toPair()
        ?: targets["filter:rights"]?.let { "filter:rights" to it }.takeIf { fallbackToFilters }
}

private fun requestCatalogFocus(model: CatalogViewModel, page: String, key: String, requester: FocusRequester): Boolean {
    val restored = requester.requestFocus()
    if (restored) model.recordFocus(page, key, restored = true)
    return restored
}
