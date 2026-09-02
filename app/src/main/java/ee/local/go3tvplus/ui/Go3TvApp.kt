@file:androidx.media3.common.util.UnstableApi

package ee.local.go3tvplus.ui

import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ee.local.go3tvplus.domain.Channel
import ee.local.go3tvplus.domain.DailyWeather
import ee.local.go3tvplus.domain.DeviceAuthState
import ee.local.go3tvplus.domain.Profile
import ee.local.go3tvplus.domain.Program
import ee.local.go3tvplus.domain.ProgramWindow
import ee.local.go3tvplus.domain.number
import ee.local.go3tvplus.domain.TransitDeparture
import ee.local.go3tvplus.domain.TransitStopSelection
import ee.local.go3tvplus.domain.WeatherForecast
import ee.local.go3tvplus.domain.WeatherLocation
import ee.local.go3tvplus.R
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun Go3TvApp(viewModel: TvViewModel, player: Player) {
    val state by viewModel.state.collectAsState()
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Go3Colors.AppBackground)) {
            when {
                state.auth == DeviceAuthState.Restoring -> StartupScreen()
                state.auth != DeviceAuthState.Approved -> PairingScreen(state.auth, viewModel::startPairing)
                state.selectedProfileId == null && state.profiles.isEmpty() && state.error != null ->
                    StartupErrorScreen(state.error.orEmpty(), viewModel::retry)
                state.selectedProfileId == null && state.profiles.isEmpty() -> StartupScreen()
                state.selectedProfileId == null -> ProfileScreen(state.profiles, viewModel::selectProfile)
                else -> PlayerScreen(
                    state = state,
                    player = player,
                    onWeatherQueryChange = viewModel::updateWeatherSearchQuery,
                    onWeatherSearch = viewModel::searchWeatherLocations,
                    onTransitStopQueryChange = viewModel::updateTransitStopSearchQuery,
                    onTransitStopSearch = viewModel::searchTransitStops,
                    onSearchInputKey = viewModel::handleFocusedSearchKey,
                )
            }
            if (state.isDemo) DemoBadge()
        }
    }
}

@Composable
private fun StartupScreen() {
    // Same backdrop as the player's startup state, so the cold-start splash
    // transitions into playback without a layout jump.
    PlaybackStartupBackdrop(loading = false)
}

@Composable
private fun StartupErrorScreen(message: String, onRetry: () -> Unit) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(message) {
        delay(150)
        retryFocus.requestFocus()
    }
    Box(
        Modifier.fillMaxSize().background(Go3Brushes.fullscreenRadial),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Go3 TV+", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Text(message, color = Go3Colors.ErrorText, fontSize = 20.sp)
            Button(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus)) {
                Text("Proovi uuesti")
            }
        }
    }
}

@Composable
private fun PairingScreen(auth: DeviceAuthState, onStart: () -> Unit) {
    val actionFocus = remember { FocusRequester() }
    LaunchedEffect(auth) {
        if (auth == DeviceAuthState.Idle || auth == DeviceAuthState.Expired || auth is DeviceAuthState.Failed) {
            repeat(4) { attempt ->
                delay(if (attempt == 0) 150 else 350)
                runCatching { actionFocus.requestFocus() }
            }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Go3Brushes.fullscreenRadial),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Go3 TV+", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            when (auth) {
                DeviceAuthState.Restoring -> Unit
                DeviceAuthState.Idle -> {
                    Text("Seo oma Go3 konto telefoniga", color = Go3Colors.TextSecondary, fontSize = 22.sp)
                    Button(onClick = onStart, modifier = Modifier.focusRequester(actionFocus)) { Text("Alusta sidumist") }
                }
                DeviceAuthState.RequestingCode -> Text("Loon sidumiskoodi…", color = Color.White, fontSize = 22.sp)
                is DeviceAuthState.AwaitingApproval -> {
                    val qr = remember(auth.qrPayload) { qrCode(auth.qrPayload) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Image(qr, "Go3 sidumise QR-kood", Modifier.size(250.dp).background(Color.White).padding(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Skänni QR-kood telefoniga", color = Color.White, fontSize = 25.sp)
                            Text("või ava", color = Go3Colors.TextFaint, fontSize = 18.sp)
                            Text(auth.verificationUrl, color = Go3Colors.TextSecondary, fontSize = 18.sp)
                            Text(auth.deviceCode, color = Go3Colors.Accent, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                            Text("Ootan telefonis kinnitamist…", color = Go3Colors.TextSecondary, fontSize = 17.sp)
                        }
                    }
                }
                DeviceAuthState.Approved -> Unit
                DeviceAuthState.Expired -> {
                    Text("Sidumiskood aegus", color = Color.White, fontSize = 22.sp)
                    Button(onClick = onStart, modifier = Modifier.focusRequester(actionFocus)) { Text("Loo uus kood") }
                }
                is DeviceAuthState.Failed -> {
                    Text(auth.message, color = Go3Colors.ErrorText, fontSize = 20.sp)
                    Button(onClick = onStart, modifier = Modifier.focusRequester(actionFocus)) { Text("Proovi uuesti") }
                }
            }
        }
    }
}

@Composable
private fun ProfileScreen(profiles: List<Profile>, onSelect: (Profile) -> Unit) {
    val firstProfileFocus = remember { FocusRequester() }
    LaunchedEffect(profiles) {
        if (profiles.isNotEmpty()) firstProfileFocus.requestFocus()
    }
    Box(
        Modifier.fillMaxSize().background(Go3Brushes.fullscreenRadial),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("GO3 PROFIIL", color = Go3Colors.Cyan, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Kes vaatab?", color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Bold)
            Text(
                "Vali vaatamisprofiil",
                color = Go3Colors.TextHint,
                fontSize = 16.sp,
            )
            if (profiles.isEmpty()) {
                Text("Laadin profiile…", color = Go3Colors.TextSecondary, fontSize = 20.sp)
            } else {
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    profiles.forEachIndexed { index, profile ->
                        Button(
                            onClick = { onSelect(profile) },
                            modifier = Modifier.width(280.dp).height(104.dp)
                                .then(if (index == 0) Modifier.focusRequester(firstProfileFocus) else Modifier),
                            colors = ButtonDefaults.colors(
                                containerColor = Go3Colors.RowIdle,
                                contentColor = Go3Colors.TextSecondary,
                                focusedContainerColor = Go3Colors.Accent,
                                focusedContentColor = Color.White,
                            ),
                            shape = ButtonDefaults.shape(
                                shape = RoundedCornerShape(Go3Radii.L),
                                focusedShape = RoundedCornerShape(Go3Radii.L),
                            ),
                            scale = ButtonDefaults.scale(focusedScale = 1.04f),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                ProfileAvatar(profile, selected = true, size = 54.dp)
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(profile.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        if (profile.isKids) "LASTE PROFIIL" else "VAATAMISPROFIIL",
                                        color = Go3Colors.TextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
                KeyHintRow("◀▶" to "vali", "OK" to "kinnita", modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
private fun PlayerScreen(
    state: TvUiState,
    player: Player,
    onWeatherQueryChange: (String) -> Unit,
    onWeatherSearch: () -> Unit,
    onTransitStopQueryChange: (String) -> Unit,
    onTransitStopSearch: () -> Unit,
    onSearchInputKey: (Int) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize().focusable(),
            factory = { context ->
                PlayerView(context).apply {
                    this.player = player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    setKeepContentOnPlayerReset(true)
                    setShutterBackgroundColor(android.graphics.Color.rgb(5, 11, 20))
                }
            },
            update = { it.player = player },
        )

        if (!state.videoVisible) {
            PlaybackStartupBackdrop(state.loading)
        }

        when (state.overlay) {
            Overlay.CHANNEL_RAIL -> ChannelRail(state)
            Overlay.GUIDE -> GuideOverlay(state)
            Overlay.APP_SETTINGS -> AppSettingsOverlay(state)
            Overlay.CHANNEL_SETTINGS -> ChannelSettingsOverlay(state)
            Overlay.PROFILE_SETTINGS -> ProfileSettingsOverlay(state)
            Overlay.AUDIO_SETTINGS -> LanguageSettingsOverlay(
                title = "HELIRAJA EELISTUS",
                description = "Kehtib kõigil kanalitel; puuduva keele korral kasutatakse kanali vaikimisi heli",
                options = AUDIO_LANGUAGE_OPTIONS,
                selectedIndex = state.menuIndex,
                activeLanguage = state.audioLanguagePreference,
            )
            Overlay.SUBTITLE_SETTINGS -> LanguageSettingsOverlay(
                title = "SUBTIITRITE EELISTUS",
                description = "Kehtib kõigil kanalitel ja järelvaatamisel",
                options = SUBTITLE_LANGUAGE_OPTIONS,
                selectedIndex = state.menuIndex,
                activeLanguage = state.subtitleLanguagePreference,
            )
            Overlay.DISPLAY_SETTINGS -> DisplaySettingsOverlay(state)
            Overlay.WEATHER_LOCATION -> WeatherLocationOverlay(
                search = state.weather.search,
                currentLocation = state.weather.location,
                onQueryChange = onWeatherQueryChange,
                onSearch = onWeatherSearch,
                onInputKey = onSearchInputKey,
            )
            Overlay.WEATHER -> WeatherOverlay(state.weather)
            Overlay.TRANSIT_STOP_SETTINGS -> TransitStopSettingsOverlay(
                search = state.transit.search,
                currentStop = state.transit.stop,
                onQueryChange = onTransitStopQueryChange,
                onSearch = onTransitStopSearch,
                onInputKey = onSearchInputKey,
            )
            Overlay.TRANSIT -> TransitOverlay(state.transit)
            Overlay.TONIGHT -> TonightOverlay(state)
            Overlay.SEEK -> SeekOverlay(state)
            Overlay.NONE -> Unit
        }

        if (state.numberInput.isNotEmpty()) {
            Text(
                state.numberInput,
                modifier = Modifier.align(Alignment.TopEnd).padding(54.dp)
                    .background(Go3Colors.PanelDark, RoundedCornerShape(Go3Radii.M)).padding(horizontal = 28.dp, vertical = 16.dp),
                color = Color.White,
                fontSize = 52.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        if (
            state.showClock && state.videoVisible && !state.loading &&
            state.overlay == Overlay.NONE && state.numberInput.isEmpty() && state.error == null
        ) {
            PlaybackClock(Modifier.align(Alignment.TopEnd).padding(top = 34.dp, end = 38.dp))
        }

        if (state.loading && state.videoVisible && (state.channels.isEmpty() || state.overlay == Overlay.NONE)) {
            val firstLoad = state.channels.isEmpty()
            Text(
                if (firstLoad) "Laadin…" else "Ühendan kanalit…",
                modifier = Modifier
                    .align(if (firstLoad) Alignment.Center else Alignment.TopEnd)
                    .padding(if (firstLoad) 0.dp else 32.dp)
                    .background(Go3Colors.SoftPanel, RoundedCornerShape(Go3Radii.L))
                    .padding(horizontal = if (firstLoad) 20.dp else 14.dp, vertical = if (firstLoad) 20.dp else 8.dp),
                color = Color.White,
                fontSize = if (firstLoad) 20.sp else 14.sp,
            )
        }

        state.notice?.let { notice ->
            Text(
                notice,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 38.dp)
                    .background(Go3Colors.NoticeSurface, RoundedCornerShape(Go3Radii.L))
                    .border(1.dp, Go3Colors.Cyan.copy(alpha = 0.7f), RoundedCornerShape(Go3Radii.L))
                    .padding(horizontal = 22.dp, vertical = 10.dp),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        state.error?.let { message ->
            ErrorBanner(message, state.errorActionIndex)
        }
    }
}

@Composable
private fun PlaybackStartupBackdrop(loading: Boolean) {
    Box(
        Modifier.fillMaxSize().background(Go3Colors.AppBackground),
    ) {
        Image(
            painter = painterResource(R.drawable.splash_background_v1),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Column(
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 150.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "Go3 TV+",
                color = Color.White,
                fontSize = 58.sp,
                fontWeight = FontWeight.Bold,
            )
            if (loading) {
                Text(
                    "Ühendan kanalit…",
                    color = Go3Colors.TextSecondary,
                    fontSize = 17.sp,
                )
            }
        }
    }
}

@Composable
private fun PlaybackClock(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val waitMs = 60_000L - (System.currentTimeMillis() % 60_000L)
            delay(waitMs.coerceAtLeast(1_000L))
            now = Instant.now()
        }
    }
    Text(
        formatTime(now),
        modifier = modifier
            .background(Go3Colors.PanelDark.copy(alpha = 0.72f), RoundedCornerShape(Go3Radii.M))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        color = Color.White,
        fontSize = 21.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun SeekOverlay(state: TvUiState) {
    // While catchup plays, describe the catchup programme — not whatever is
    // currently airing live on the channel.
    val watching = state.catchupProgram
    val channel = state.channels.firstOrNull { it.id == (watching?.channelId ?: state.currentChannelId) }
    val playbackInstant = state.seek.liveOffsetMs
        ?.takeIf { state.seek.isLive }
        ?.let { Instant.now().minusMillis(it) }
        ?: Instant.now()
    val program = watching ?: state.programsFor(state.currentChannelId)
        .firstOrNull { ProgramWindow.isCurrent(it, playbackInstant) }
    val canStartOver = watching == null && state.seek.isLive && program != null && (
        StartOverResolver.liveRewindMs(state.seek.positionMs, program.startsAt, playbackInstant) != null ||
            program.catchupAvailable
        )
    val timelineStart = if (watching != null && !state.seek.isLive) {
        watching.startsAt
    } else {
        playbackInstant.minusMillis(state.seek.positionMs.coerceAtLeast(0L))
    }
    val programBoundaries = ProgramWindow.boundaryFractions(
        state.programsFor(channel?.id),
        timelineStart,
        state.seek.durationMs,
    )
    val progress = if (state.seek.durationMs > 0L) {
        (state.seek.positionMs.toFloat() / state.seek.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val liveLabel = when {
        !state.seek.isLive -> formatPlaybackDuration(state.seek.durationMs)
        (state.seek.liveOffsetMs ?: Long.MAX_VALUE) <= 5_000L -> "OTSE"
        else -> "−${formatPlaybackDuration(state.seek.liveOffsetMs ?: 0L)} OTSEST"
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth(0.82f)
                .padding(bottom = 42.dp)
                .clip(RoundedCornerShape(Go3Radii.L))
                .background(Go3Brushes.seekPanel)
                .padding(horizontal = 26.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${channel?.number ?: ""}  ${channel?.name.orEmpty()}" +
                        if (watching != null) "  •  ${formatDate(watching.startsAt)} ${formatTime(watching.startsAt)}" else "",
                    color = Go3Colors.Cyan,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.seek.playing) "▶" else "Ⅱ",
                        color = if (state.seek.playing) Go3Colors.Cyan else Color.White,
                        fontSize = 20.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                program?.title ?: "Ajanihe",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            program?.let {
                Text(
                    "${formatTime(it.startsAt)}–${formatTime(it.endsAt)}  •  ${formatProgramDuration(ProgramWindow.durationMinutes(it))}",
                    color = Go3Colors.Cyan,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!it.description.isNullOrBlank()) {
                    Text(
                        it.description,
                        color = Go3Colors.TextSecondary,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(8.dp).align(Alignment.Center)
                        .background(Go3Colors.ProgressTrack, RoundedCornerShape(Go3Radii.XS)),
                ) {
                    if (progress > 0f) {
                        Box(
                            Modifier.fillMaxWidth(progress).fillMaxHeight()
                                .background(Go3Brushes.progressFill, RoundedCornerShape(Go3Radii.XS)),
                        )
                    }
                }
                programBoundaries.forEach { fraction ->
                    Box(
                        Modifier
                            .offset(
                                x = (maxWidth * fraction - 1.dp)
                                    .coerceIn(0.dp, (maxWidth - 2.dp).coerceAtLeast(0.dp)),
                            )
                            .align(Alignment.CenterStart)
                            .width(2.dp)
                            .height(14.dp)
                            .background(Color.White.copy(alpha = 0.82f), RoundedCornerShape(1.dp)),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formatPlaybackDuration(state.seek.positionMs), color = Color.White, fontSize = 14.sp)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                    if (canStartOver) {
                        KeyHintRow("▲" to "algusest", "◀▶" to "${state.seekStepSeconds} s", "OK" to "esita/paus", "BACK" to "sulge")
                    } else {
                        KeyHintRow("◀▶" to "${state.seekStepSeconds} s", "OK" to "esita/paus", "BACK" to "sulge")
                    }
                }
                Text(liveLabel, color = if (liveLabel == "OTSE") Go3Colors.Cyan else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ChannelRail(state: TvUiState) {
    val railChannels = state.visibleChannels
    val selectedIndex = state.railIndex.coerceIn(0, railChannels.lastIndex.coerceAtLeast(0))
    val first = (selectedIndex - 3).coerceAtLeast(0)
    val visible = railChannels.drop(first).take(6)
    Box(
        Modifier.fillMaxHeight().width(430.dp)
            .background(Go3Brushes.railPanel)
            .padding(horizontal = 32.dp, vertical = 38.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.favoritesOnly) "KANALID  •  LEMMIKUD" else "KANALID  •  KÕIK",
                    color = Go3Colors.Accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (state.favoritesOnly) "◀  ★ Lemmikud" else "◀  ☆ Näita lemmikuid",
                    modifier = Modifier
                        .background(
                            if (state.favoritesOnly) Go3Colors.Accent else Go3Colors.ChipIdle,
                            RoundedCornerShape(Go3Radii.L),
                        )
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = if (state.favoritesOnly) FontWeight.Bold else FontWeight.Normal,
                )
            }
            if (railChannels.isEmpty()) {
                Text("Lemmikkanaleid pole veel valitud", color = Color.White, fontSize = 18.sp)
                Text("Lisa lemmik täisekraanil ◀ seadistusest", color = Go3Colors.TextSecondary, fontSize = 14.sp)
            }
            visible.forEachIndexed { offset, channel ->
                val index = first + offset
                val selected = index == selectedIndex
                val now = state.programsFor(channel.id).nowProgram()
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (selected) Go3Colors.Accent else Color.Transparent, RoundedCornerShape(Go3Radii.S))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${channel.number}", color = if (selected) Color.White else Go3Colors.TextFaint, fontSize = 18.sp, modifier = Modifier.width(42.dp))
                    Column {
                        Text(channel.name, color = Color.White, fontSize = 21.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        Text(now?.title ?: "Saatekava puudub", color = if (selected) Color.White else Go3Colors.TextSecondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            KeyHintRow("▶" to "telekava", "OK" to "vali")
        }
    }
}

@Composable
private fun GuideOverlay(state: TvUiState) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now()
            delay(30_000L)
        }
    }
    val guideChannels = state.visibleChannels
    val selectedChannel = guideChannels.getOrNull(state.guideChannelIndex)
    val selectedPrograms = state.programsFor(selectedChannel?.id)
    val selectedProgram = selectedPrograms.getOrNull(state.guideProgramIndex)
    val anchor = state.guideAnchor ?: now
    val windowStart = state.guideWindowStart
        ?: ProgramWindow.guideWindowStart(anchor, ZoneId.systemDefault())
    val windowEnd = windowStart.plus(ProgramWindow.GUIDE_WINDOW_DURATION)
    val visibleCount = 6
    val first = (state.guideChannelIndex - 2)
        .coerceIn(0, (guideChannels.size - visibleCount).coerceAtLeast(0))
    val visibleChannels = guideChannels.drop(first).take(visibleCount)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.82f)
                .clip(RoundedCornerShape(topStart = Go3Radii.XL, topEnd = Go3Radii.XL))
                .background(Go3Brushes.guideSheet),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("TELEKAVA", color = Go3Colors.Cyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (state.favoritesOnly) "  ★ LEMMIKUD" else "  ☆ KÕIK",
                            color = if (state.favoritesOnly) Go3Colors.Favorite else Go3Colors.TextHint,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    GuideDayBadge(anchor, now)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        selectedChannel?.name.orEmpty(),
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    KeyHintRow("◀▶" to "aeg", "▲▼" to "kanal", "OK" to "vaata • hoia: lemmikud")
                }
            }

            Row(Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("KANAL", color = Go3Colors.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(206.dp))
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val halfHourSlots = 8
                    repeat(halfHourSlots + 1) { slot ->
                        val slotTime = windowStart.plus(ProgramWindow.GUIDE_WINDOW_STEP.multipliedBy(slot.toLong()))
                        val x = maxWidth * (slot.toFloat() / halfHourSlots)
                        if (slotTime.atZone(ZoneId.systemDefault()).minute == 0) {
                            val labelWidth = 48.dp
                            Text(
                                formatTime(slotTime),
                                modifier = Modifier
                                    .width(labelWidth)
                                    .offset(x = (x - labelWidth / 2).coerceIn(0.dp, (maxWidth - labelWidth).coerceAtLeast(0.dp)))
                                    .align(Alignment.CenterStart),
                                color = Go3Colors.TextHint,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            Box(
                                Modifier
                                    .offset(x = (x - 0.5.dp).coerceIn(0.dp, (maxWidth - 1.dp).coerceAtLeast(0.dp)))
                                    .align(Alignment.CenterStart)
                                    .width(1.dp)
                                    .height(6.dp)
                                    .background(Go3Colors.TextFaint.copy(alpha = 0.7f)),
                            )
                        }
                    }
                    if (!now.isBefore(windowStart) && now.isBefore(windowEnd)) {
                        val totalMillis = Duration.between(windowStart, windowEnd).toMillis().coerceAtLeast(1).toFloat()
                        val nowFraction = (Duration.between(windowStart, now).toMillis() / totalMillis).coerceIn(0f, 1f)
                        val badgeWidth = 54.dp
                        val badgeX = (maxWidth * nowFraction - badgeWidth / 2)
                            .coerceIn(0.dp, (maxWidth - badgeWidth).coerceAtLeast(0.dp))
                        Text(
                            formatTime(now),
                            modifier = Modifier
                                .offset(x = badgeX)
                                .align(Alignment.CenterStart)
                                .background(Go3Colors.NowBadge, RoundedCornerShape(Go3Radii.S))
                                .border(1.dp, Go3Colors.Cyan.copy(alpha = 0.75f), RoundedCornerShape(Go3Radii.S))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            visibleChannels.forEachIndexed { offset, channel ->
                val channelIndex = first + offset
                key(channel.id) {
                    GuideChannelRow(
                        channel = channel,
                        channelIndex = channelIndex,
                        selectedChannelIndex = state.guideChannelIndex,
                        selectedProgramId = selectedProgram?.id,
                        programs = state.programsFor(channel.id),
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        now = now,
                        scheduledReminderIds = state.scheduledReminderIds,
                        scheduledAutoTuneIds = state.scheduledAutoTuneIds,
                    )
                }
                if (offset < visibleChannels.lastIndex) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Go3Colors.GridLine))
                }
            }

            Row(
                Modifier.fillMaxWidth().height(62.dp)
                    .background(Go3Colors.InfoBar)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectedProgram == null) {
                    Text("Selle kanali saatekava puudub", color = Go3Colors.TextHint, fontSize = 16.sp)
                } else {
                    Column(Modifier.width(112.dp)) {
                        Text("${formatTime(selectedProgram.startsAt)}–${formatTime(selectedProgram.endsAt)}", color = Go3Colors.Cyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(formatDate(selectedProgram.startsAt), color = Go3Colors.TextFaint, fontSize = 11.sp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(selectedProgram.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(selectedProgram.description.orEmpty(), color = Go3Colors.TextSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    val status = when {
                        ProgramWindow.isCurrent(selectedProgram, now) -> "OTSE"
                        selectedProgram.endsAt.isBefore(now) && selectedProgram.catchupAvailable -> "JÄRELVAATAMINE"
                        selectedProgram.endsAt.isBefore(now) -> "LÕPPENUD"
                        else -> "TULEKUL"
                    }
                    Text(
                        status,
                        modifier = Modifier.padding(start = 14.dp).background(Go3Colors.StatusChip, RoundedCornerShape(Go3Radii.L)).padding(horizontal = 11.dp, vertical = 5.dp),
                        color = if (status == "OTSE") Go3Colors.Cyan else Go3Colors.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            GuideLegend(
                reminderActive = selectedProgram != null && selectedProgram.id in state.scheduledReminderIds,
                autoTuneActive = selectedProgram != null && selectedProgram.id in state.scheduledAutoTuneIds,
            )
        }
    }
}

@Composable
private fun GuideLegend(reminderActive: Boolean, autoTuneActive: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(30.dp)
            .background(Go3Colors.InfoBar)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        LegendItem(Go3Colors.KeyRed, "eelmine päev")
        LegendItem(Go3Colors.KeyGreen, "järgmine päev")
        LegendItem(Go3Colors.KeyYellow, "meeldetuletus", active = reminderActive)
        LegendItem(Go3Colors.KeyBlue, "lülitu kanalile", active = autoTuneActive)
    }
}

@Composable
private fun LegendItem(dotColor: Color, label: String, active: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(
            Modifier.size(9.dp).background(dotColor, RoundedCornerShape(50)).then(
                if (active) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(50)) else Modifier,
            ),
        )
        Text(
            label,
            color = if (active) Color.White else Go3Colors.TextHint,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun GuideDayBadge(anchor: Instant, now: Instant) {
    val zone = ZoneId.systemDefault()
    val dayDiff = anchor.atZone(zone).toLocalDate().toEpochDay() - now.atZone(zone).toLocalDate().toEpochDay()
    val label = when (dayDiff) {
        0L -> "TÄNA"
        1L -> "HOMME"
        -1L -> "EILE"
        else -> null
    }
    val text = if (label != null) "$label  •  ${formatDate(anchor)}" else formatDate(anchor)
    if (dayDiff == 0L) {
        Text(text, color = Go3Colors.TextHint, fontSize = 12.sp)
    } else {
        // Away from today: highlight the badge so the jumped-to day is obvious.
        Text(
            text,
            modifier = Modifier
                .background(Go3Colors.Accent, RoundedCornerShape(Go3Radii.XS))
                .padding(horizontal = 6.dp, vertical = 1.dp),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun GuideChannelRow(
    channel: Channel,
    channelIndex: Int,
    selectedChannelIndex: Int,
    selectedProgramId: String?,
    programs: List<Program>,
    windowStart: Instant,
    windowEnd: Instant,
    now: Instant,
    scheduledReminderIds: Set<String>,
    scheduledAutoTuneIds: Set<String>,
) {
    val selectedChannel = channelIndex == selectedChannelIndex
    val rowColor = if (selectedChannel) Go3Colors.SelectedRow else Go3Colors.GuideRowTint
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(rowColor),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.width(220.dp).padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${channel.number}",
                modifier = Modifier.width(40.dp).background(if (selectedChannel) Go3Colors.Accent else Go3Colors.ChipIdle, RoundedCornerShape(Go3Radii.XS)).padding(vertical = 4.dp),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                channel.name,
                modifier = Modifier.padding(start = 9.dp).weight(1f),
                color = if (selectedChannel) Color.White else Go3Colors.TextSecondary,
                fontSize = 15.sp,
                fontWeight = if (selectedChannel) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        GuideTimelineCanvas(
            programs = programs,
            selectedProgramId = if (selectedChannel) selectedProgramId else null,
            windowStart = windowStart,
            windowEnd = windowEnd,
            now = now,
            scheduledReminderIds = scheduledReminderIds,
            scheduledAutoTuneIds = scheduledAutoTuneIds,
            modifier = Modifier.weight(1f).fillMaxHeight().background(Go3Colors.TimelineBackground),
        )
    }
}

@Composable
private fun GuideTimelineCanvas(
    programs: List<Program>,
    selectedProgramId: String?,
    windowStart: Instant,
    windowEnd: Instant,
    now: Instant,
    scheduledReminderIds: Set<String>,
    scheduledAutoTuneIds: Set<String>,
    modifier: Modifier,
) {
    val fillPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    val borderPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE } }
    val timePaint = remember { TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD } }
    val titlePaint = remember { TextPaint(Paint.ANTI_ALIAS_FLAG) }
    val linePaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    val gridPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    val progressTrackPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    val progressPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    val actionHaloPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    val reminderPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    val autoTunePaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    val drawingPrograms = remember(programs, selectedProgramId, windowStart, windowEnd) {
        programs
            .filter { ProgramWindow.overlaps(it, windowStart, windowEnd) }
            .sortedBy { if (it.id == selectedProgramId) 1 else 0 }
    }

    Canvas(modifier) {
        val canvas = drawContext.canvas.nativeCanvas
        val totalMillis = Duration.between(windowStart, windowEnd).toMillis().coerceAtLeast(1).toFloat()
        val horizontalPadding = 8.dp.toPx()
        val minimumWidth = 30.dp.toPx()
        timePaint.textSize = 11.sp.toPx()
        titlePaint.textSize = 13.sp.toPx()
        borderPaint.strokeWidth = 2.dp.toPx()
        borderPaint.color = Go3Colors.CellHighlightBorder.toArgb()
        linePaint.color = Go3Colors.Cyan.copy(alpha = 0.45f).toArgb()
        gridPaint.color = Go3Colors.GridLine.toArgb()
        progressTrackPaint.color = Go3Colors.InkShadow.toArgb()
        progressPaint.color = Go3Colors.Cyan.toArgb()
        actionHaloPaint.color = Go3Colors.InkShadow.toArgb()
        reminderPaint.color = Go3Colors.KeyYellow.toArgb()
        autoTunePaint.color = Go3Colors.KeyBlue.toArgb()

        drawingPrograms.forEach { program ->
                val clippedStart = if (program.startsAt.isBefore(windowStart)) windowStart else program.startsAt
                val clippedEnd = if (program.endsAt.isAfter(windowEnd)) windowEnd else program.endsAt
                val startFraction = (Duration.between(windowStart, clippedStart).toMillis() / totalMillis).coerceIn(0f, 1f)
                val endFraction = (Duration.between(windowStart, clippedEnd).toMillis() / totalMillis).coerceIn(0f, 1f)
                val left = size.width * startFraction
                val naturalRight = size.width * endFraction
                val right = naturalRight.coerceAtLeast(left + minimumWidth).coerceAtMost(size.width)
                if (right <= left) return@forEach
                val selected = program.id == selectedProgramId
                fillPaint.color = when {
                    selected -> Go3Colors.Accent.toArgb()
                    ProgramWindow.isCurrent(program, now) -> Go3Colors.ProgramCellLive.toArgb()
                    else -> Go3Colors.ProgramCell.toArgb()
                }
                val rect = RectF(left, 0f, right, size.height)
                canvas.drawRect(rect, fillPaint)
                if (selected) {
                    val inset = 1.dp.toPx()
                    canvas.drawRect(RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset), borderPaint)
                } else {
                    // Table look: cells separated by a hairline instead of a gap.
                    canvas.drawRect(right - 1.dp.toPx(), rect.top, right, rect.bottom, gridPaint)
                }

                val availableTextWidth = (right - left - horizontalPadding * 2).coerceAtLeast(1f)
                timePaint.color = if (selected) Color.White.toArgb() else Go3Colors.TextSecondary.toArgb()
                titlePaint.color = Color.White.toArgb()
                val timeBaseline = 4.dp.toPx() + timePaint.textSize
                canvas.drawText(formatTime(program.startsAt), left + horizontalPadding, timeBaseline, timePaint)
                val title = TextUtils.ellipsize(program.title, titlePaint, availableTextWidth, TextUtils.TruncateAt.END).toString()
                canvas.drawText(title, left + horizontalPadding, timeBaseline + titlePaint.textSize + 2.dp.toPx(), titlePaint)

                if (ProgramWindow.isCurrent(program, now)) {
                    val durationMillis = Duration.between(program.startsAt, program.endsAt).toMillis().coerceAtLeast(1).toFloat()
                    val progress = (Duration.between(program.startsAt, now).toMillis() / durationMillis).coerceIn(0f, 1f)
                    val barInset = 6.dp.toPx()
                    val barHeight = 3.dp.toPx()
                    val barLeft = left + barInset
                    val barRight = (right - barInset).coerceAtLeast(barLeft)
                    val barBottom = rect.bottom - 3.dp.toPx()
                    val track = RectF(barLeft, barBottom - barHeight, barRight, barBottom)
                    canvas.drawRoundRect(track, barHeight / 2, barHeight / 2, progressTrackPaint)
                    if (progress > 0f) {
                        val filled = RectF(barLeft, track.top, barLeft + (barRight - barLeft) * progress, barBottom)
                        canvas.drawRoundRect(filled, barHeight / 2, barHeight / 2, progressPaint)
                    }
                }

            }

        // With a gapless table the current-time marker must sit above the cells;
        // it crosses them as a thin translucent line, under the action markers.
        if (!now.isBefore(windowStart) && now.isBefore(windowEnd)) {
            val nowFraction = (Duration.between(windowStart, now).toMillis() / totalMillis).coerceIn(0f, 1f)
            val lineX = size.width * nowFraction
            canvas.drawRect(lineX, 0f, lineX + 1.dp.toPx(), size.height, linePaint)
        }

        // Draw programme actions in a dedicated top layer. Short programme cards
        // may overlap because of their minimum width, but must never hide a marker.
        drawingPrograms.forEach { program ->
            val hasReminder = program.id in scheduledReminderIds
            val hasAutoTune = program.id in scheduledAutoTuneIds
            if (!hasReminder && !hasAutoTune) return@forEach

            val clippedStart = if (program.startsAt.isBefore(windowStart)) windowStart else program.startsAt
            val clippedEnd = if (program.endsAt.isAfter(windowEnd)) windowEnd else program.endsAt
            val startFraction = (Duration.between(windowStart, clippedStart).toMillis() / totalMillis).coerceIn(0f, 1f)
            val endFraction = (Duration.between(windowStart, clippedEnd).toMillis() / totalMillis).coerceIn(0f, 1f)
            val left = size.width * startFraction
            val right = (size.width * endFraction)
                .coerceAtLeast(left + minimumWidth)
                .coerceAtMost(size.width)
            if (right <= left) return@forEach

            val dotRadius = 3.5.dp.toPx()
            val haloRadius = dotRadius + 1.5.dp.toPx()
            val dotGap = 3.dp.toPx()
            val dotY = 7.dp.toPx()
            val rightDotX = right - 8.dp.toPx()
            val reminderX = if (hasReminder && hasAutoTune) rightDotX - dotRadius * 2 - dotGap else rightDotX
            if (hasReminder) {
                canvas.drawCircle(reminderX, dotY, haloRadius, actionHaloPaint)
                canvas.drawCircle(reminderX, dotY, dotRadius, reminderPaint)
            }
            if (hasAutoTune) {
                canvas.drawCircle(rightDotX, dotY, haloRadius, actionHaloPaint)
                canvas.drawCircle(rightDotX, dotY, dotRadius, autoTunePaint)
            }
        }
    }
}

@Composable
private fun AppSettingsOverlay(state: TvUiState) {
    fun subtitle(setting: AppSetting): String = when (setting) {
        AppSetting.PROFILE -> state.profiles.firstOrNull { it.id == state.selectedProfileId }?.name ?: "Praegune Go3 profiil"
        AppSetting.CHANNELS -> "Lemmikud, numbrid ja järjekord"
        AppSetting.AUDIO -> "${languageLabel(AUDIO_LANGUAGE_OPTIONS, state.audioLanguagePreference)} • kõigil kanalitel"
        AppSetting.SUBTITLES -> "${languageLabel(SUBTITLE_LANGUAGE_OPTIONS, state.subtitleLanguagePreference)} • kõigil kanalitel"
        AppSetting.DISPLAY ->
            "Kell ${if (state.showClock) "sees" else "väljas"} • info ${state.channelInfoSeconds} s • kerimine ${state.seekStepSeconds} s"
        AppSetting.WEATHER -> state.weather.location.let { "${it.name}${it.area?.let { area -> " • $area" }.orEmpty()}" }
        AppSetting.TRANSIT -> "${state.transit.stop.name} • ${state.transit.stop.platforms.joinToString { it.code }}"
        AppSetting.REFRESH_PACKAGE -> "Kontrolli Go3 tellimust ja peidetud kanaleid uuesti"
    }
    val rows = AppSetting.entries
    val listState = rememberLazyListState()
    LaunchedEffect(state.appSettingsIndex) {
        listState.followSelection(state.appSettingsIndex.coerceIn(rows.indices))
    }
    Box(Modifier.fillMaxSize().background(Go3Colors.Scrim), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier.fillMaxHeight().width(760.dp)
                .background(Go3Brushes.settingsPanel)
                .padding(horizontal = 44.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.fillMaxWidth()) {
                OverlayHeader(
                    "SEADED",
                    "Go3 TV+",
                    keyHints = listOf("▲▼" to "vali", "OK" to "ava/muuda", "BACK" to "sulge"),
                )
                Text(
                    "v${ee.local.go3tvplus.BuildConfig.VERSION_NAME}",
                    modifier = Modifier.align(Alignment.TopEnd),
                    color = Go3Colors.TextFaint,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 14.dp),
            ) {
                itemsIndexed(rows) { index, setting ->
                    val selected = index == state.appSettingsIndex
                    SettingsRow(selected, verticalPadding = 11.dp) {
                        Column(Modifier.weight(1f)) {
                            Text(setting.title, color = Color.White, fontSize = 20.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
                            Text(subtitle(setting), color = if (selected) Color.White else Go3Colors.TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("›", color = if (selected) Color.White else Go3Colors.TextFaint, fontSize = 30.sp)
                    }
                }
            }
        }
    }
}
@Composable
private fun DisplaySettingsOverlay(state: TvUiState) {
    CenteredMenuPanel(width = 760.dp) {
        OverlayHeader(
            "EKRAAN JA JUHTIMINE",
            "Vaatamisvaate eelistused",
            "Sinine nupp lülitab kella ka otse täisekraanvaates",
            keyHints = listOf("◀▶" to "muuda", "OK" to "muuda", "BACK" to "tagasi"),
        )
        Spacer(Modifier.height(6.dp))
        DisplaySetting.entries.forEachIndexed { index, setting ->
            SettingsRow(selected = state.menuIndex == index, verticalPadding = 8.dp) {
                Column(Modifier.weight(1f)) {
                    Text(setting.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(setting.description, color = Go3Colors.TextSecondary, fontSize = 13.sp)
                }
                when (setting) {
                    DisplaySetting.CLOCK -> SettingToggle(state.showClock)
                    DisplaySetting.CHANNEL_INFO -> SettingValue("${state.channelInfoSeconds} s")
                    DisplaySetting.SEEK_OVERLAY -> SettingValue("${state.seekOverlaySeconds} s")
                    DisplaySetting.SEEK_STEP -> SettingValue("${state.seekStepSeconds} s")
                }
            }
        }
    }
}
@Composable
private fun WeatherLocationOverlay(
    search: SearchState<WeatherLocation>,
    currentLocation: WeatherLocation,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onInputKey: (Int) -> Unit,
) {
    CenteredMenuPanel(width = 760.dp) {
        OverlayHeader(
            "ILMA ASUKOHT",
            "Otsi asulat",
            "Valik salvestatakse sellesse telerisse",
            keyHints = listOf("▲▼" to "vali", "OK" to "kinnita/otsi", "BACK" to "tagasi"),
        )
        Spacer(Modifier.height(6.dp))
        SearchInput(
            query = search.query,
            placeholder = "Näiteks Suurupi või Muraste",
            accent = Go3Colors.Cyan,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            onInputKey = onInputKey,
        )
        SearchStatus(search, searching = "Otsin asukohta…", idle = "Kirjuta asula nimi ja vali klaviatuuril Otsi")
        search.results.take(5).forEachIndexed { index, location ->
            val selected = index == search.index
            SettingsRow(selected, verticalPadding = 9.dp) {
                Column(Modifier.weight(1f)) {
                    Text(location.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(location.area ?: "Eesti", color = Go3Colors.TextSecondary, fontSize = 13.sp)
                }
                if (location == currentLocation) RowBadge("PRAEGUNE", selected)
                else Text("›", color = if (selected) Color.White else Go3Colors.TextFaint, fontSize = 28.sp)
            }
        }
    }
}

@Composable
private fun TransitStopSettingsOverlay(
    search: SearchState<TransitStopSelection>,
    currentStop: TransitStopSelection,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onInputKey: (Int) -> Unit,
) {
    CenteredMenuPanel(width = 760.dp) {
        OverlayHeader(
            "BUSSIPEATUSE VALIK",
            "Otsi peatust nime järgi",
            "Vaikimisi kasutatakse Muraste mõlemat sõidusuunda",
            keyHints = listOf("▲▼" to "vali", "OK" to "kinnita/otsi", "BACK" to "tagasi"),
        )
        Spacer(Modifier.height(6.dp))
        SearchInput(
            query = search.query,
            placeholder = "Näiteks Muraste või Tabasalu",
            accent = Go3Colors.KeyGreen,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            onInputKey = onInputKey,
        )
        SearchStatus(search, searching = "Otsin peatusi…", idle = "Kirjuta peatuse nimi ja vali klaviatuuril Otsi")
        search.results.take(6).forEachIndexed { index, stop ->
            val selected = index == search.index
            SettingsRow(selected, verticalPadding = 8.dp) {
                Column(Modifier.weight(1f)) {
                    Text(stop.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Peatused ${stop.platforms.joinToString { it.code.ifBlank { it.id.substringAfter(':') } }}",
                        color = Go3Colors.TextSecondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (stop == currentStop) RowBadge("PRAEGUNE", selected)
                else Text("›", color = if (selected) Color.White else Go3Colors.TextFaint, fontSize = 28.sp)
            }
        }
    }
}

/** Tekstiväli puldiklaviatuuri jaoks: fookus ja klaviatuur avanevad ise, nooled ja OK lähevad view modelile. */
@Composable
private fun SearchInput(
    query: String,
    placeholder: String,
    accent: Color,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onInputKey: (Int) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(250)
        focusRequester.requestFocus()
        keyboard?.show()
    }
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .searchInputKeyHandler(onInputKey) { keyboard?.hide() }
            .background(Go3Colors.RowIdle, RoundedCornerShape(Go3Radii.M))
            .border(1.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(Go3Radii.M))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 20.sp),
        singleLine = true,
        cursorBrush = SolidColor(accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            keyboard?.hide()
            onSearch()
        }),
        decorationBox = { inner ->
            Box {
                if (query.isBlank()) Text(placeholder, color = Go3Colors.TextFaint, fontSize = 20.sp)
                inner()
            }
        },
    )
}

@Composable
private fun SearchStatus(search: SearchState<*>, searching: String, idle: String) {
    when {
        search.loading -> Text(searching, color = Go3Colors.TextSecondary, fontSize = 16.sp)
        search.error != null -> Text(search.error, color = Go3Colors.ErrorText, fontSize = 15.sp)
        search.results.isEmpty() -> Text(idle, color = Go3Colors.TextSecondary, fontSize = 15.sp)
    }
}
private fun Modifier.searchInputKeyHandler(
    onInputKey: (Int) -> Unit,
    hideKeyboard: () -> Unit,
): Modifier = onPreviewKeyEvent { composeEvent ->
    val event = composeEvent.nativeKeyEvent
    val handled = event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
        event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
        event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        event.keyCode == KeyEvent.KEYCODE_ENTER ||
        event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
    if (handled && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
        hideKeyboard()
        onInputKey(event.keyCode)
    }
    handled
}

@Composable
private fun WeatherOverlay(weather: WeatherState) {
    val forecast = weather.forecast
    Box(Modifier.fillMaxSize().background(Go3Colors.Scrim), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth(0.84f)
                .clip(RoundedCornerShape(Go3Radii.XL))
                .background(Go3Brushes.menuCard)
                .border(1.dp, Go3Colors.Cyan.copy(alpha = 0.24f), RoundedCornerShape(Go3Radii.XL))
                .padding(horizontal = 34.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (forecast == null) {
                OverlayHeader("ILM", weather.location.name)
                Text(
                    weather.error ?: if (weather.loading) "Värskendan ilmateadet…" else "Ilmateade pole veel saadaval",
                    color = if (weather.error == null) Go3Colors.TextSecondary else Go3Colors.ErrorText,
                    fontSize = 18.sp,
                )
            } else {
                WeatherCurrent(forecast)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WeatherMetric("TAJUTAV", formatTemperature(forecast.current.apparentTemperatureC), Modifier.weight(1f))
                    WeatherMetric("TUUL", "${oneDecimal(forecast.current.windSpeedMs)} m/s ${windDirection(forecast.current.windDirectionDegrees)}", Modifier.weight(1f))
                    WeatherMetric("PUHANGUD", "${oneDecimal(forecast.current.windGustMs)} m/s", Modifier.weight(1f))
                    WeatherMetric("NIISKUS", "${forecast.current.humidityPercent}%", Modifier.weight(1f))
                    WeatherMetric("SADEMED", "${oneDecimal(forecast.current.precipitationMm)} mm", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    forecast.hours.take(6).forEach { hour ->
                        Column(
                            Modifier.weight(1f)
                                .background(Go3Colors.RowIdle, RoundedCornerShape(Go3Radii.M))
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(hour.time.format(WEATHER_HOUR_FORMAT), color = Go3Colors.TextSecondary, fontSize = 13.sp)
                            Image(
                                painter = painterResource(weatherKind(hour.weatherCode, isDay = hour.time.hour in 7..21).vectorRes),
                                contentDescription = weatherDescription(hour.weatherCode),
                                modifier = Modifier.size(42.dp),
                                colorFilter = ColorFilter.tint(Color.White),
                            )
                            Text(formatTemperature(hour.temperatureC), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text("${hour.precipitationProbability}% sade", color = Go3Colors.Cyan, fontSize = 11.sp)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Andmed: Open-Meteo", color = Go3Colors.TextFaint, fontSize = 11.sp, modifier = Modifier.weight(1f))
                ColorKeyDot(Go3Colors.KeyYellow, "või BACK sulgeb")
            }
        }
    }
}
@Composable
private fun WeatherCurrent(forecast: WeatherForecast) {
    val current = forecast.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AnimatedWeatherIcon(current.weatherCode, current.isDay, Modifier.size(122.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.width(190.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                forecast.location.name.uppercase(),
                color = Go3Colors.Cyan,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(formatTemperature(current.temperatureC), color = Color.White, fontSize = 58.sp, fontWeight = FontWeight.Bold)
            Text(weatherDescription(current.weatherCode), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            forecast.location.area?.let { Text(it, color = Go3Colors.TextSecondary, fontSize = 14.sp) }
        }
        Spacer(Modifier.width(18.dp))
        WeatherDailySummary(
            days = forecast.days.drop(1).take(4),
            fetchedAt = forecast.fetchedAt,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun WeatherDailySummary(days: List<DailyWeather>, fetchedAt: Instant, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "JÄRGMISED PÄEVAD",
                color = Go3Colors.TextFaint,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (fetchedAt != Instant.EPOCH) {
                Text("Uuendatud ${formatTime(fetchedAt)}", color = Go3Colors.TextFaint, fontSize = 10.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            days.forEach { day ->
                Column(
                    Modifier
                        .weight(1f)
                        .background(Go3Colors.RowIdle.copy(alpha = 0.72f), RoundedCornerShape(Go3Radii.M))
                        .padding(horizontal = 5.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        weatherDayLabel(day),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Image(
                        painter = painterResource(weatherKind(day.weatherCode, isDay = true).vectorRes),
                        contentDescription = weatherDescription(day.weatherCode),
                        modifier = Modifier.size(35.dp),
                        colorFilter = ColorFilter.tint(Color.White),
                    )
                    Text(
                        "${formatTemperature(day.minimumTemperatureC)}  ${formatTemperature(day.maximumTemperatureC)}",
                        color = Go3Colors.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun TransitOverlay(transit: TransitState) {
    val board = transit.board
    val selectedPlatform = transit.stop.platforms.getOrNull(transit.directionIndex)
        ?: transit.stop.platforms.firstOrNull()
    val stopCode = selectedPlatform?.code.orEmpty()
    val departures = board?.departures.orEmpty().filter { it.stopCode == stopCode }
    val listState = rememberLazyListState()
    LaunchedEffect(transit.directionIndex, transit.departureIndex, departures.size) {
        if (departures.isNotEmpty()) {
            listState.followSelection(transit.departureIndex.coerceIn(departures.indices))
        }
    }
    Box(Modifier.fillMaxSize().background(Go3Colors.Scrim), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth(0.82f)
                .fillMaxHeight(0.94f)
                .clip(RoundedCornerShape(Go3Radii.XL))
                .background(Go3Brushes.menuCard)
                .border(1.dp, Go3Colors.KeyGreen.copy(alpha = 0.28f), RoundedCornerShape(Go3Radii.XL))
                .padding(horizontal = 28.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("JÄRGMISED BUSSID", color = Go3Colors.KeyGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        (board?.stopName ?: transit.stop.name).uppercase(),
                        color = Color.White,
                        fontSize = 29.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                board?.takeIf { it.fetchedAt != Instant.EPOCH }?.let {
                    Text("Uuendatud ${formatTime(it.fetchedAt)}", color = Go3Colors.TextFaint, fontSize = 11.sp)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                transit.stop.platforms.forEachIndexed { index, platform ->
                    val platformDepartures = board?.departures.orEmpty().filter { it.stopCode == platform.code }
                    val labels = transitDirectionLabels(transit.stop.name, platform.code, platformDepartures, index)
                    TransitDirectionTab(
                        title = labels.first,
                        subtitle = labels.second,
                        selected = transit.directionIndex == index,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            when {
                board == null && transit.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Laadin ${transit.stop.name} väljumisi…", color = Go3Colors.TextSecondary, fontSize = 20.sp)
                }
                board == null && transit.error != null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(transit.error, color = Go3Colors.ErrorText, fontSize = 18.sp, textAlign = TextAlign.Center)
                }
                departures.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Selles suunas rohkem väljumisi ei leitud", color = Go3Colors.TextSecondary, fontSize = 18.sp)
                }
                else -> LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(
                        departures,
                        key = { _, departure -> "${departure.stopCode}-${departure.routeShortName}-${departure.departureAt}" },
                    ) { index, departure ->
                        TransitDepartureRow(
                            departure = departure,
                            selected = index == transit.departureIndex,
                        )
                    }
                }
            }

            transit.error?.takeIf { board != null }?.let {
                Text(it, color = Go3Colors.ErrorText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Andmed: Peatus.ee / ÜTRIS", color = Go3Colors.TextFaint, fontSize = 11.sp, modifier = Modifier.weight(1f))
                KeyHintRow("◀▶" to "suund", "▲▼" to "väljumine", "OK" to "värskenda")
                Spacer(Modifier.width(16.dp))
                ColorKeyDot(Go3Colors.KeyGreen, "või BACK sulgeb")
            }
        }
    }
}
@Composable
private fun TransitDirectionTab(title: String, subtitle: String, selected: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(if (selected) Go3Colors.SelectedRow else Go3Colors.RowIdle, RoundedCornerShape(Go3Radii.M))
            .then(
                if (selected) Modifier.border(1.dp, Go3Colors.KeyGreen.copy(alpha = 0.8f), RoundedCornerShape(Go3Radii.M))
                else Modifier,
            )
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(title, color = if (selected) Color.White else Go3Colors.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = if (selected) Go3Colors.KeyGreen else Go3Colors.TextFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TransitDepartureRow(departure: TransitDeparture, selected: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Go3Colors.Accent else Go3Colors.RowIdle, RoundedCornerShape(Go3Radii.M))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.width(84.dp)) {
            Text(formatTime(departure.departureAt), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(transitDateLabel(departure.departureAt), color = if (selected) Color.White else Go3Colors.TextFaint, fontSize = 10.sp)
        }
        Box(
            Modifier.width(60.dp)
                .background(if (selected) Color.White.copy(alpha = 0.18f) else Go3Colors.StatusChip, RoundedCornerShape(Go3Radii.S))
                .padding(vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(departure.routeShortName, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(
                "${departure.origin}  →  ${departure.destination}",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    departure.cancelled -> "Väljumine tühistatud"
                    departure.realtime -> "Reaalaja prognoos"
                    else -> "Sõiduplaani aeg"
                },
                color = when {
                    selected -> Color.White
                    departure.cancelled -> Go3Colors.ErrorText
                    departure.realtime -> Go3Colors.KeyGreen
                    else -> Go3Colors.TextFaint
                },
                fontSize = 10.sp,
            )
        }
        Text(
            relativeTimeLabel(departure.departureAt),
            color = if (selected) Color.White else Go3Colors.KeyGreen,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(96.dp),
        )
    }
}

private fun transitDirectionLabels(
    stopName: String,
    stopCode: String,
    departures: List<TransitDeparture>,
    index: Int,
): Pair<String, String> {
    val destinations = departures.map(TransitDeparture::destination).distinct().take(2)
    val origins = departures.map(TransitDeparture::origin).distinct().take(2)
    val title = when {
        stopName.equals("Muraste", ignoreCase = true) && stopCode == "21524-1" -> "TALLINNA POOLE"
        stopName.equals("Muraste", ignoreCase = true) && stopCode == "21525-1" -> "VÄÄNA-JÕESUU POOLE"
        destinations.isNotEmpty() -> destinations.joinToString(" · ").uppercase(ESTONIAN)
        else -> "SUUND ${index + 1}"
    }
    val subtitle = when {
        origins.isNotEmpty() -> "Saabub: ${origins.joinToString(" · ")}"
        destinations.isNotEmpty() -> destinations.joinToString(" · ")
        else -> "Peatus $stopCode"
    }
    return title to subtitle
}

private fun transitDateLabel(instant: Instant): String {
    val date = instant.atZone(ZoneId.systemDefault()).toLocalDate()
    val today = java.time.LocalDate.now()
    return when (date) {
        today -> "TÄNA"
        today.plusDays(1) -> "HOMME"
        else -> date.format(SHORT_DATE_FORMAT).uppercase(ESTONIAN)
    }
}
private fun relativeTimeLabel(instant: Instant): String {
    val minutes = Duration.between(Instant.now(), instant).toMinutes().coerceAtLeast(0)
    return when {
        minutes <= 1 -> "KOHE"
        minutes < 60 -> "$minutes min"
        minutes < 24 * 60 -> "${minutes / 60} h ${minutes % 60} min"
        else -> "${minutes / (24 * 60)} päeva"
    }
}

/** Kerib LazyColumni nii, et valitud rida jääb alati nähtavale (ka pikkadel hüpetel). */
private suspend fun LazyListState.followSelection(selectedIndex: Int) {
    val layout = layoutInfo
    val visibleItems = layout.visibleItemsInfo
    val selectedItem = visibleItems.firstOrNull { it.index == selectedIndex }
    when {
        selectedItem != null && selectedItem.offset < layout.viewportStartOffset ->
            animateScrollBy((selectedItem.offset - layout.viewportStartOffset).toFloat())

        selectedItem != null && selectedItem.offset + selectedItem.size > layout.viewportEndOffset ->
            animateScrollBy((selectedItem.offset + selectedItem.size - layout.viewportEndOffset).toFloat())

        visibleItems.isNotEmpty() && selectedIndex < visibleItems.first().index ->
            animateScrollToItem(selectedIndex)

        visibleItems.isNotEmpty() && selectedIndex > visibleItems.last().index -> {
            val rowHeight = visibleItems.last().size
            val visibleHeight = layout.viewportEndOffset - layout.viewportStartOffset
            val rowTopAtBottom = (visibleHeight - rowHeight).coerceAtLeast(0)
            animateScrollToItem(selectedIndex, scrollOffset = -rowTopAtBottom)
        }
    }
}

@Composable
private fun TonightOverlay(state: TvUiState) {
    val entries = state.tonight.entries
    val now = if (state.tonight.now == Instant.EPOCH) Instant.now() else state.tonight.now
    val listState = rememberLazyListState()
    LaunchedEffect(state.tonight.index, entries.size) {
        if (entries.isNotEmpty()) {
            listState.followSelection(state.tonight.index.coerceIn(entries.indices))
        }
    }
    Box(Modifier.fillMaxSize().background(Go3Colors.Scrim), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth(0.82f)
                .fillMaxHeight(0.94f)
                .clip(RoundedCornerShape(Go3Radii.XL))
                .background(Go3Brushes.menuCard)
                .border(1.dp, Go3Colors.KeyRed.copy(alpha = 0.28f), RoundedCornerShape(Go3Radii.XL))
                .padding(horizontal = 28.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("TÄNA ÕHTUL", color = Go3Colors.KeyRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(tonightDateLabel(now), color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    if (state.favoriteChannelIds.isEmpty()) "Kõik kanalid" else "Lemmikkanalid",
                    color = Go3Colors.TextFaint,
                    fontSize = 11.sp,
                )
            }

            if (entries.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Tänase õhtu kava pole veel laaditud", color = Go3Colors.TextSecondary, fontSize = 20.sp)
                        Text(
                            "Telekava uueneb taustal — proovi hetke pärast uuesti",
                            color = Go3Colors.TextFaint,
                            fontSize = 14.sp,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(
                        entries,
                        key = { _, entry -> "${entry.channel.id}-${entry.program.id}" },
                    ) { index, entry ->
                        TonightProgramRow(
                            entry = entry,
                            now = now,
                            selected = index == state.tonight.index,
                            reminderSet = entry.program.id in state.scheduledReminderIds,
                            autoTuneSet = entry.program.id in state.scheduledAutoTuneIds,
                        )
                    }
                }
                val description = entries.getOrNull(state.tonight.index)?.program?.description
                    ?.takeIf(String::isNotBlank)
                Text(
                    description ?: "Sellel saatel kirjeldus puudub",
                    color = if (description != null) Go3Colors.TextSecondary else Go3Colors.TextFaint,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Go3Colors.GuideRowTint, RoundedCornerShape(Go3Radii.M))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (entries.isEmpty()) "" else "${entries.size} saadet",
                    color = Go3Colors.TextFaint,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                KeyHintRow("▲▼" to "vali", "OK" to "vaata / tuleta meelde")
                Spacer(Modifier.width(14.dp))
                ColorKeyDot(Go3Colors.KeyYellow, "meeldetuletus")
                Spacer(Modifier.width(10.dp))
                ColorKeyDot(Go3Colors.KeyBlue, "automaatlülitus")
                Spacer(Modifier.width(10.dp))
                ColorKeyDot(Go3Colors.KeyRed, "või BACK sulgeb")
            }
        }
    }
}

@Composable
private fun TonightProgramRow(
    entry: TonightEntry,
    now: Instant,
    selected: Boolean,
    reminderSet: Boolean,
    autoTuneSet: Boolean,
) {
    val program = entry.program
    val live = ProgramWindow.isCurrent(program, now)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Go3Colors.Accent else Go3Colors.RowIdle, RoundedCornerShape(Go3Radii.M))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.width(78.dp)) {
            Text(formatTime(program.startsAt), color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(
                "kuni ${formatTime(program.endsAt)}",
                color = if (selected) Color.White.copy(alpha = 0.8f) else Go3Colors.TextFaint,
                fontSize = 10.sp,
            )
        }
        Box(
            Modifier
                .width(128.dp)
                .background(
                    if (selected) Color.White.copy(alpha = 0.18f) else Go3Colors.StatusChip,
                    RoundedCornerShape(Go3Radii.S),
                )
                .padding(horizontal = 8.dp, vertical = 5.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                "${entry.channel.number} ${entry.channel.name}",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                program.title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (live) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (selected) Color.White.copy(alpha = 0.3f) else Go3Colors.ProgressTrack),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(programProgress(program, now))
                                .background(if (selected) Color.White else Go3Colors.KeyGreen),
                        )
                    }
                    Text(
                        "lõpeb ${formatTime(program.endsAt)}",
                        color = if (selected) Color.White else Go3Colors.TextFaint,
                        fontSize = 10.sp,
                    )
                }
            } else {
                Text(
                    program.description?.takeIf(String::isNotBlank) ?: " ",
                    color = if (selected) Color.White.copy(alpha = 0.85f) else Go3Colors.TextFaint,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (reminderSet) TonightActionChip("TULETA MEELDE", Go3Colors.KeyYellow, selected)
        if (autoTuneSet) TonightActionChip("LÜLITUB", Go3Colors.KeyBlue, selected)
        Text(
            if (live) "KÄIB" else relativeTimeLabel(program.startsAt),
            color = when {
                selected -> Color.White
                live -> Go3Colors.KeyGreen
                else -> Go3Colors.Cyan
            },
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(88.dp),
        )
    }
}

@Composable
private fun TonightActionChip(text: String, color: Color, selected: Boolean) {
    Text(
        text,
        color = if (selected) Color.White else color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background((if (selected) Color.White else color).copy(alpha = 0.18f), RoundedCornerShape(Go3Radii.XS))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
private fun ColorKeyDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(5.dp)))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Go3Colors.TextHint, fontSize = 13.sp)
    }
}

private fun tonightDateLabel(now: Instant): String =
    now.atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", ESTONIAN))
        .replaceFirstChar { it.titlecase(ESTONIAN) }
private fun programProgress(program: Program, now: Instant): Float {
    val total = Duration.between(program.startsAt, program.endsAt).toMillis().coerceAtLeast(1)
    val elapsed = Duration.between(program.startsAt, now).toMillis().coerceIn(0, total)
    return elapsed.toFloat() / total
}

@Composable
private fun AnimatedWeatherIcon(code: Int, isDay: Boolean, modifier: Modifier = Modifier) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(weatherKind(code, isDay).animationRes))
    val progress by animateLottieCompositionAsState(composition, iterations = LottieConstants.IterateForever)
    LottieAnimation(composition = composition, progress = { progress }, modifier = modifier)
}

@Composable
private fun WeatherMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.background(Go3Colors.RowIdle.copy(alpha = 0.78f), RoundedCornerShape(Go3Radii.M))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(label, color = Go3Colors.TextFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Open-Meteo WMO koodid rühmitatuna ikoonideks; sama rühm annab nii vektori kui animatsiooni. */
private enum class WeatherKind(val vectorRes: Int, val animationRes: Int) {
    CLEAR_DAY(R.drawable.weather_clear_day, R.raw.weather_clear_day),
    CLEAR_NIGHT(R.drawable.weather_clear_night, R.raw.weather_clear_night),
    FOG(R.drawable.weather_fog, R.raw.weather_fog),
    RAIN(R.drawable.weather_rain, R.raw.weather_rain),
    SNOW(R.drawable.weather_snow, R.raw.weather_snow),
    THUNDERSTORMS(R.drawable.weather_thunderstorms, R.raw.weather_thunderstorms),
    CLOUDY(R.drawable.weather_cloudy, R.raw.weather_cloudy),
}

private fun weatherKind(code: Int, isDay: Boolean): WeatherKind = when {
    code == 0 && isDay -> WeatherKind.CLEAR_DAY
    code == 0 -> WeatherKind.CLEAR_NIGHT
    code in 45..48 -> WeatherKind.FOG
    code in 51..67 || code in 80..82 -> WeatherKind.RAIN
    code in 71..77 || code in 85..86 -> WeatherKind.SNOW
    code >= 95 -> WeatherKind.THUNDERSTORMS
    else -> WeatherKind.CLOUDY
}
private fun weatherDescription(code: Int): String = when (code) {
    0 -> "Selge"
    1 -> "Peamiselt selge"
    2 -> "Vahelduv pilvisus"
    3 -> "Pilvine"
    45, 48 -> "Udu"
    in 51..57 -> "Uduvihm"
    in 61..67 -> "Vihm"
    in 71..77 -> "Lumesadu"
    in 80..82 -> "Vihmahood"
    85, 86 -> "Lumehood"
    in 95..99 -> "Äike"
    else -> "Muutlik ilm"
}

private fun formatTemperature(value: Double) = "${value.roundToInt()}°"
private fun weatherDayLabel(day: DailyWeather): String = when (day.date.dayOfWeek.value) {
    1 -> "E"
    2 -> "T"
    3 -> "K"
    4 -> "N"
    5 -> "R"
    6 -> "L"
    else -> "P"
}
private fun oneDecimal(value: Double) = String.format(Locale.US, "%.1f", value)
private fun windDirection(degrees: Int): String {
    val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return directions[((degrees.mod(360) + 22) / 45).mod(8)]
}

private val WEATHER_HOUR_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun SettingToggle(checked: Boolean) {
    Box(
        Modifier
            .width(52.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (checked) Go3Colors.Cyan else Go3Colors.ChipIdle)
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(22.dp).background(Color.White, RoundedCornerShape(11.dp)))
    }
}

@Composable
private fun SettingValue(value: String) {
    Text(
        "‹  $value  ›",
        modifier = Modifier
            .background(Go3Colors.ChipIdle, RoundedCornerShape(Go3Radii.L))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun ProfileSettingsOverlay(state: TvUiState) {
    CenteredMenuPanel {
        OverlayHeader(
            "GO3 PROFIIL",
            "Kes vaatab?",
            "Profiili vahetamine värskendab sinu kanalipaketti",
        )
        Spacer(Modifier.height(12.dp))
        if (state.profiles.isEmpty()) {
            Text("Laadin Go3 profiile…", color = Go3Colors.TextSecondary, fontSize = 18.sp)
        }
        state.profiles.forEachIndexed { index, profile ->
            val selected = index == state.menuIndex
            val active = profile.id == state.selectedProfileId
            SettingsRow(selected, verticalPadding = 12.dp) {
                ProfileAvatar(profile, selected, size = 48.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        profile.name,
                        color = Color.White,
                        fontSize = 21.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    )
                    Text(
                        if (profile.isKids) "Laste profiil" else "Vaatamisprofiil",
                        color = if (selected) Color.White.copy(alpha = 0.78f) else Go3Colors.TextSecondary,
                        fontSize = 13.sp,
                    )
                }
                if (active) RowBadge("PRAEGUNE", selected)
                else Text("›", color = if (selected) Color.White else Go3Colors.TextFaint, fontSize = 30.sp)
            }
        }
        KeyHintRow("▲▼" to "vali", "OK" to "kinnita", "BACK" to "sulge", modifier = Modifier.align(Alignment.End))
    }
}

@Composable
private fun ProfileAvatar(profile: Profile, selected: Boolean, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).background(
            if (selected) Color.White.copy(alpha = 0.16f) else Go3Colors.ChipIdle,
            RoundedCornerShape(Go3Radii.M),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            profile.name.trim().firstOrNull()?.uppercase() ?: "•",
            color = if (selected) Color.White else Go3Colors.Cyan,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun LanguageSettingsOverlay(
    title: String,
    description: String,
    options: List<Pair<String?, String>>,
    selectedIndex: Int,
    activeLanguage: String?,
) {
    CenteredMenuPanel {
        OverlayHeader(title, hint = description)
        Spacer(Modifier.height(6.dp))
        options.forEachIndexed { index, (language, label) ->
            val selected = index == selectedIndex
            val active = language == activeLanguage
            SettingsRow(selected, verticalPadding = 15.dp) {
                Text(label, color = Color.White, fontSize = 21.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                if (active) RowBadge("EELISTATUD", selected)
            }
        }
        KeyHintRow("▲▼" to "vali", "OK" to "kinnita", "BACK" to "sulge", modifier = Modifier.align(Alignment.End))
    }
}

@Composable
private fun ChannelSettingsOverlay(state: TvUiState) {
    val selectedIndex = state.menuIndex.coerceIn(0, state.channels.lastIndex.coerceAtLeast(0))
    val first = (selectedIndex - 3).coerceAtLeast(0)
    val visible = state.channels.drop(first).take(7)
    CenteredMenuPanel(width = 700.dp) {
        OverlayHeader(
            "KANALID",
            "Kanalite seadistus",
            keyHints = listOf("▲▼" to "vali", "◀▶" to "muuda numbrit", "OK" to "lemmik", "0–9" to "uus number"),
        )
        Spacer(Modifier.height(4.dp))
        visible.forEachIndexed { offset, channel ->
            val index = first + offset
            val selected = index == selectedIndex
            SettingsRow(selected, verticalPadding = 10.dp) {
                Text("${channel.number}", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(70.dp))
                Text(channel.name, color = Color.White, fontSize = 20.sp, modifier = Modifier.weight(1f))
                Text(if (channel.id in state.favoriteChannelIds) "★ Lemmik" else "☆", color = if (channel.id in state.favoriteChannelIds) Go3Colors.Favorite else Go3Colors.TextSecondary, fontSize = 18.sp)
            }
        }
        KeyHintRow("BACK" to "sulge", modifier = Modifier.align(Alignment.End))
    }
}

@Composable
private fun ErrorBanner(message: String, selectedAction: Int) {
    Row(
        Modifier.fillMaxWidth().background(Go3Colors.ErrorSurface).padding(horizontal = 30.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(message, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
        ErrorAction("Proovi uuesti", selectedAction == 0)
        ErrorAction("Sulge", selectedAction == 1)
    }
}

@Composable
private fun ErrorAction(label: String, selected: Boolean) {
    Text(
        label,
        modifier = Modifier
            .background(if (selected) Go3Colors.Accent else Go3Colors.ChipIdle, RoundedCornerShape(Go3Radii.L))
            .padding(horizontal = 18.dp, vertical = 8.dp),
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
    )
}

@Composable
private fun DemoBadge() {
    Text(
        "DEMO – Go3 API pole ühendatud",
        modifier = Modifier.padding(14.dp).background(Go3Colors.NoticeSurface, RoundedCornerShape(Go3Radii.S)).padding(horizontal = 12.dp, vertical = 7.dp),
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
    )
}

private fun List<Program>.nowProgram(): Program? {
    val now = Instant.now()
    return firstOrNull { ProgramWindow.isCurrent(it, now) }
}

private val ESTONIAN: Locale = Locale.forLanguageTag("et-EE")
private val SHORT_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d. MMM", ESTONIAN)
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val dateFormatter = SHORT_DATE_FORMAT.withZone(ZoneId.systemDefault())
private fun formatTime(instant: Instant): String = timeFormatter.format(instant)
private fun formatDate(instant: Instant): String = dateFormatter.format(instant).uppercase(ESTONIAN)

private fun formatPlaybackDuration(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

private fun formatProgramDuration(totalMinutes: Long): String {
    val minutes = totalMinutes.coerceAtLeast(0L)
    val hours = minutes / 60L
    val remainingMinutes = minutes % 60L
    return when {
        hours == 0L -> "$remainingMinutes min"
        remainingMinutes == 0L -> "$hours h"
        else -> "$hours h $remainingMinutes min"
    }
}
