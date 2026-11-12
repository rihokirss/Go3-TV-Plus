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

private val Ink = Color(0xFF090D17)
private val Muted = Color(0xFFA7B0C1)
private val Accent = Color(0xFF8FE7F4)
private val Shape = RoundedCornerShape(10.dp)
private val LocalContentAutofocus = staticCompositionLocalOf { true }

@Composable
fun CatalogOverlay(model: CatalogViewModel) {
    val state by model.state.collectAsState()
    val saved = rememberSaveableStateHolderCompat()
    BackHandler(state.visible) { model.back() }
    if (state.visible) {
        val menuFocus = remember { FocusRequester() }
        val contentFocus = remember { FocusRequester() }
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
                Column(Modifier.width(180.dp).fillMaxHeight().background(Ink.copy(alpha = .95f)).padding(start = 20.dp, end = 16.dp, top = 35.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("GO3 AIR", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    Text("SINU HETK. SINU VALIK.", color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
                    Spacer(Modifier.height(27.dp))
                    CatalogSection.entries.forEach { section ->
                        val selected = state.section == section
                        Button(onClick = { model.select(section) },
                            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) model.openMenu() }.then(if (selected || (isTvMenu && section == CatalogSection.TV)) Modifier.focusRequester(menuFocus) else Modifier)
                                .onPreviewKeyEvent {
                                    val event = it.nativeKeyEvent
                                    if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && page != null) {
                                        model.contentFocus(); contentFocus.requestFocus(); true
                                    } else false
                                },
                            colors = ButtonDefaults.colors(containerColor = if (selected) Color.White.copy(alpha = .1f) else Color.Transparent,
                                focusedContainerColor = Color.White, focusedContentColor = Ink, contentColor = if (selected) Color.White else Muted),
                            contentPadding = PaddingValues(horizontal = 15.dp, vertical = 10.dp),
                            scale = ButtonDefaults.scale(focusedScale = 1f)) {
                            Text(section.label, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Video jätkab taustal", color = Muted.copy(alpha = .65f), fontSize = 10.sp, modifier = Modifier.padding(bottom = 25.dp))
                }
                if (page != null) saved.SaveableStateProvider(page.key) {
                    CompositionLocalProvider(LocalContentAutofocus provides !state.menu) {
                    Column(Modifier.weight(1f).fillMaxHeight().focusRequester(contentFocus).focusGroup()) {
                        if (page.detailId != null) DetailContent(model, state, menuFocus) { hero = it }
                        else BrowseContent(model, state, menuFocus) { hero = it }
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
private fun BrowseContent(model: CatalogViewModel, state: CatalogUiState, menu: FocusRequester, focus: (CatalogTitle) -> Unit) {
    val page = state.page ?: return
    val focusTargets = remember(page.key) { mutableMapOf<String, FocusRequester>() }
    LaunchedEffect(state.entry, state.menu) {
        if (!state.menu) { delay(30); (focusTargets[model.focusedKeys[page.key]] ?: focusTargets.values.firstOrNull())?.requestFocus() }
    }
    val rows by produceState<List<CatalogRow>>(emptyList(), state.titles, state.progress, state.list, page.filter, state.section) {
        value = withContext(Dispatchers.Default) { state.rows() }
    }
    val filtered by produceState<List<CatalogTitle>>(emptyList(), state.titles, state.list, state.searchIds, page.filter, page.group, state.progress) {
        value = withContext(Dispatchers.Default) {
        val base = state.filtered()
        when (page.group) {
            "continue" -> base.filter { state.progressFor(it)?.let { p -> p.positionMs > 0 && p.continueEligible && (if (it.kind == CatalogKind.SERIES) state.seriesHasMore(it) else !p.watched) } == true }
            "list" -> base.filter { it.id in state.list }
            else -> base
        }
        }
    }
    var focused by remember { mutableStateOf<CatalogTitle?>(null) }
    val active = focused ?: filtered.firstOrNull()
    LaunchedEffect(active?.id) { active?.let(focus) }
    Column(Modifier.fillMaxSize()) {
        if (page.grid) {
            Column(Modifier.padding(horizontal = 32.dp, vertical = 16.dp)) {
                Text(page.title, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                if (state.section == CatalogSection.SEARCH) SearchField(state.query, model::query, model.focusedKeys[page.key] == null || model.focusedKeys[page.key] == "search", focusTargets) { model.recordFocus(page.key, "search") }
                Filters(model, state)
                Status(state, model)
            }
            val saved = model.scrollPositions[page.key + ":grid"]
            val scroll = rememberLazyGridState(saved?.first ?: 0, saved?.second ?: 0)
            LaunchedEffect(scroll) { snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { model.scrollPositions[page.key + ":grid"] = it } }
            LazyVerticalGrid(GridCells.Fixed(5), state = scroll, contentPadding = PaddingValues(start = 32.dp, end = 32.dp, bottom = 30.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.weight(1f)) {
                itemsIndexed(filtered, key = { _, it -> it.id }) { index, item ->
                    Poster(model, page.key, "grid:${item.id}", item, state.progressFor(item), index % 5 == 0, menu, focusTargets,
                        initial = state.section != CatalogSection.SEARCH && index == (saved?.first ?: 0), onFocus = { focused = item }, onClick = { model.open(item) })
                }
                if (filtered.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyCatalog(state) }
            }
        } else {
            val saved = model.scrollPositions[page.key + ":rows"]
            val scroll = rememberLazyListState(saved?.first ?: 0, saved?.second ?: 0)
            LaunchedEffect(scroll) { snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { model.scrollPositions[page.key + ":rows"] = it } }
            Column(Modifier.fillMaxWidth().height(200.dp).padding(start = 32.dp, end = 105.dp, top = 25.dp)) {
                Text(page.title.uppercase(), color = Accent, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(9.dp))
                Text(active?.title ?: page.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(active?.let(::metadata).orEmpty(), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 7.dp))
                Text(active?.description.orEmpty(), color = Color.White.copy(alpha = .8f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
            }
            Column(Modifier.padding(horizontal = 32.dp)) { Status(state, model); Filters(model, state) }
            LazyColumn(state = scroll, contentPadding = PaddingValues(top = 12.dp, bottom = 25.dp), verticalArrangement = Arrangement.spacedBy(19.dp), modifier = Modifier.weight(1f)) {
                items(rows, key = { it.key }) { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(row.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 32.dp))
                        val rowKey = page.key + ":" + row.key
                        val position = model.scrollPositions[rowKey]
                        val rowScroll = rememberLazyListState(position?.first ?: 0, position?.second ?: 0)
                        LaunchedEffect(rowScroll) { snapshotFlow { rowScroll.firstVisibleItemIndex to rowScroll.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { model.scrollPositions[rowKey] = it } }
                        LazyRow(state = rowScroll, contentPadding = PaddingValues(horizontal = 32.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            itemsIndexed(row.items.take(24), key = { _, item -> item.id }) { index, item ->
                                Poster(model, page.key, "${row.key}:${item.id}", item, state.progressFor(item), index == 0, menu, focusTargets,
                                    initial = row.key == rows.firstOrNull()?.key && index == 0, onFocus = { focused = item }, onClick = { model.open(item) })
                            }
                            item(key = "all") {
                                Button(onClick = { model.all(row) }, modifier = restoredCatalogFocus(model, page.key, "${row.key}:all", targets = focusTargets).width(116.dp).height(157.dp),
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

@Composable
private fun SearchField(query: String, change: (String) -> Unit, initial: Boolean, targets: MutableMap<String, FocusRequester>, focused: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val allowAutofocus = LocalContentAutofocus.current
    RegisterCatalogFocus(targets, "search", requester)
    LaunchedEffect(Unit) { if (allowAutofocus && initial) { delay(30); requester.requestFocus() } }
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
    BasicTextField(query, change, singleLine = true, cursorBrush = SolidColor(Accent), textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 18.sp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.moveFocus(FocusDirection.Down) }),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).background(Color.White.copy(alpha = .09f), Shape).border(1.dp, Color.White.copy(alpha = .25f), Shape)
            .focusRequester(requester).onFocusChanged { if (it.isFocused) { focused(); keyboard?.show() } }.padding(14.dp),
        decorationBox = { inner -> Box { if (query.isEmpty()) Text("Otsi pealkirja või algpealkirja…", color = Muted, fontSize = 17.sp); inner() } })
}

@Composable
private fun Filters(model: CatalogViewModel, state: CatalogUiState) {
    val filter = state.page?.filter ?: return
    var choosing by remember { mutableStateOf<String?>(null) }
    val genres = remember(state.titles) { state.titles.filter { it.kind != CatalogKind.EPISODE }.flatMap { it.genres }.distinct().sorted() }
    val years = remember(state.titles) { state.titles.mapNotNull { it.year }.distinct().sortedDescending() }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
        item { FilterButton(if (filter.allRights) "Kogu kataloog" else "Minu pakett") { model.filter(filter.copy(allRights = !filter.allRights)) } }
        if (state.page?.grid == true) item { FilterButton(when(filter.kind) { CatalogKind.MOVIE -> "Filmid"; CatalogKind.SERIES -> "Seriaalid"; else -> "Filmid ja seriaalid" }) { choosing = "kind" } }
        item { FilterButton(filter.genre ?: "Kõik žanrid") { choosing = "genre" } }
        item { FilterButton(filter.year?.toString() ?: "Kõik aastad") { choosing = "year" } }
        item { FilterButton(filter.sort.label) { choosing = "sort" } }
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
@Composable private fun FilterButton(label: String, click: () -> Unit) {
    Button(onClick = click, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), scale = ButtonDefaults.scale(focusedScale = 1f), colors = ButtonDefaults.colors(containerColor = Color.White.copy(alpha = .09f), contentColor = Muted)) { Text(label, fontSize = 11.sp) }
}
@Composable private fun Status(state: CatalogUiState, model: CatalogViewModel) {
    if (state.error != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        Text(state.error, color = Color(0xFFFFC9AD), fontSize = 12.sp, modifier = Modifier.weight(1f))
        FilterButton("Proovi uuesti", model::refresh)
    } else if (state.history.error != null) Text(state.history.error, color = Muted, fontSize = 11.sp)
    else if (state.loading || state.searching) Text(if (state.searching) "Otsin…" else "Värskendan valikut…", color = Muted, fontSize = 11.sp)
}
@Composable private fun EmptyCatalog(state: CatalogUiState) {
    Text(when { state.error != null -> "Salvestatud valik on siin ka ühenduse taastumiseni."; state.loading -> "Laadin esimest valikut…"; state.section == CatalogSection.LIST -> "Lisa lemmikud teose detailvaates oma nimekirja."; state.section == CatalogSection.SEARCH -> "Selle otsingu ja filtritega teoseid ei leitud."; else -> "Selle filtriga teoseid ei leitud. Proovi kogu kataloogi või värskenda valikut." }, color = Muted, fontSize = 16.sp, modifier = Modifier.padding(vertical = 30.dp))
}

@Composable
private fun Poster(model: CatalogViewModel, page: String, key: String, item: CatalogTitle, progress: CatalogProgress?, first: Boolean, menu: FocusRequester, targets: MutableMap<String, FocusRequester>, initial: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
    val requester = remember { FocusRequester() }
    RegisterCatalogFocus(targets, key, requester)
    var focused by remember { mutableStateOf(false) }
    val lastKey = model.focusedKeys[page]
    val allowAutofocus = LocalContentAutofocus.current
    LaunchedEffect(Unit) { if (allowAutofocus && (lastKey == key || (lastKey == null && initial))) { delay(30); requester.requestFocus(); model.contentFocus() } }
    Column(Modifier.width(116.dp).focusRequester(requester).onFocusChanged {
        focused = it.isFocused
        if (it.isFocused) { model.recordFocus(page, key); model.contentFocus(); onFocus() }
    }.onPreviewKeyEvent {
        if (first && it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { model.openMenu(); menu.requestFocus(); true } else false
    }.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(116.dp).height(157.dp).clip(Shape).background(Color(0xFF202839)).then(if (focused) Modifier.border(3.dp, Color.White, Shape) else Modifier)) {
            Text(item.title, color = Muted, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center).padding(12.dp), maxLines = 5)
            AsyncImage(item.poster, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (item.right != ViewingRight.AVAILABLE) Text(when(item.right) { ViewingRight.LOCKED -> "🔒"; ViewingRight.EXPIRED -> "AEGUNUD"; else -> "?" }, color = Color.White, fontSize = 14.sp, modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).background(Ink.copy(alpha = .8f), RoundedCornerShape(4.dp)).padding(5.dp))
            if (progress != null) ProgressBar(progress.fraction, Modifier.align(Alignment.BottomStart))
        }
        Text(item.title, color = if (focused) Color.White else Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
    }
}
@Composable private fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .25f))) { Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(Accent)) }
}
private fun metadata(item: CatalogTitle): String = listOfNotNull(item.year?.toString(), item.age?.let { "$it+" }, item.durationSeconds?.takeIf { it > 0 }?.let { "${it / 60} min" }, item.genres.firstOrNull()).joinToString("   ·   ")

@Composable
private fun DetailContent(model: CatalogViewModel, state: CatalogUiState, menu: FocusRequester, focus: (CatalogTitle) -> Unit) {
    val item = state.detail ?: return
    val page = state.page ?: return
    val position = model.scrollPositions[page.key]
    val scroll = rememberLazyListState(position?.first ?: 0, position?.second ?: 0)
    LaunchedEffect(scroll) { snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { model.scrollPositions[page.key] = it } }
    val progress = state.progressFor(item)
    val episodes = remember(state.titles, state.selectedSeasons, item.id) { state.titles.filter { it.seriesId == item.id && it.seasonId == state.selectedSeasons[item.id] }.sortedBy { it.episode ?: Int.MAX_VALUE } }
    LaunchedEffect(item.id) { focus(item) }
    LazyColumn(state = scroll, contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 35.dp, bottom = 35.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(if (item.kind == CatalogKind.SERIES) "SERIAAL" else "FILM", color = Accent, fontSize = 11.sp, letterSpacing = 2.sp)
            Text(item.title, color = Color.White, fontSize = 35.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(metadata(item), color = Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp))
            Text(item.description.orEmpty(), color = Color.White.copy(alpha = .85f), fontSize = 14.sp, lineHeight = 21.sp, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(.92f))
            if (item.audio.isNotEmpty() || item.subtitles.isNotEmpty()) Text(listOfNotNull(item.audio.takeIf { it.isNotEmpty() }?.joinToString(", ", "Heli: "), item.subtitles.takeIf { it.isNotEmpty() }?.joinToString(", ", "Subtiitrid: ")).joinToString("  ·  "), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
            if (item.right != ViewingRight.AVAILABLE) Text(when(item.right) { ViewingRight.LOCKED -> "🔒 See teos ei kuulu sinu paketti"; ViewingRight.UNKNOWN -> "Vaatamisõigus on kontrollimata"; ViewingRight.EXPIRED -> "See teos ei ole praegu saadaval"; else -> "" }, color = Color(0xFFFFC9AD), fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                item { Button(onClick = { model.play(item) }, modifier = restoredCatalogFocus(model, page.key, "play", initial = true).onPreviewKeyEvent { if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { model.openMenu(); menu.requestFocus(); true } else false }) { Text(if (progress != null && !progress.watched && progress.positionMs > 0) "Jätka" else "Vaata") } }
                item { Button(onClick = { model.play(item, true) }, modifier = restoredCatalogFocus(model, page.key, "restart")) { Text("Alusta algusest") } }
                item { Button(onClick = { model.toggleList(item) }, modifier = restoredCatalogFocus(model, page.key, "list")) { Text(if (item.id in state.list) "Eemalda nimekirjast" else "+ Minu nimekiri") } }
            }
        }
        item { Status(state, model) }
        if (item.kind == CatalogKind.SERIES) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(state.seasons[item.id].orEmpty(), key = { it.id }) { season ->
                        Button(onClick = { model.selectSeason(item.id, season.id) }, modifier = restoredCatalogFocus(model, page.key, "season:${season.id}"), colors = ButtonDefaults.colors(containerColor = if (state.selectedSeasons[item.id] == season.id) Color.White.copy(alpha = .2f) else Color.White.copy(alpha = .07f))) { Text(if (season.number > 0) "${season.number}. hooaeg" else season.title) }
                    }
                }
            }
            items(episodes, key = { it.id }) { episode ->
                val episodeFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { if (!state.menu && model.focusedKeys[page.key] == episode.id) { delay(30); episodeFocus.requestFocus() } }
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
                        Button(onClick = { model.play(episode, true) }, modifier = restoredCatalogFocus(model, page.key, "restart:${episode.id}").padding(end = 12.dp)) { Text("Algusest", fontSize = 11.sp) }
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
    val allowAutofocus = LocalContentAutofocus.current
    LaunchedEffect(Unit) {
        val previous = model.focusedKeys[page]
        if (allowAutofocus && (previous == key || (previous == null && initial))) { delay(30); requester.requestFocus() }
    }
    return Modifier.focusRequester(requester).onFocusChanged { if (it.isFocused) model.recordFocus(page, key) }
}

@Composable
private fun RegisterCatalogFocus(targets: MutableMap<String, FocusRequester>?, key: String, requester: FocusRequester) {
    DisposableEffect(targets, key, requester) {
        targets?.set(key, requester)
        onDispose { if (targets?.get(key) === requester) targets.remove(key) }
    }
}
