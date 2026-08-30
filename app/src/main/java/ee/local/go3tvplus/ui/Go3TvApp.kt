@file:androidx.media3.common.util.UnstableApi

package ee.local.go3tvplus.ui

import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ee.local.go3tvplus.domain.Channel
import ee.local.go3tvplus.domain.DeviceAuthState
import ee.local.go3tvplus.domain.Profile
import ee.local.go3tvplus.domain.Program
import ee.local.go3tvplus.domain.ProgramWindow
import ee.local.go3tvplus.R
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun Go3TvApp(viewModel: TvViewModel, player: Player) {
    val state by viewModel.state.collectAsState()
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Go3Colors.AppBackground)) {
            when {
                state.auth != DeviceAuthState.Approved -> PairingScreen(state.auth, viewModel::startPairing)
                state.selectedProfileId == null && state.profiles.isEmpty() -> StartupScreen()
                state.selectedProfileId == null -> ProfileScreen(state.profiles, viewModel::selectProfile)
                else -> PlayerScreen(state, player)
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
private fun PairingScreen(auth: DeviceAuthState, onStart: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Go3Brushes.fullscreenRadial),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Go3 TV+", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            when (auth) {
                DeviceAuthState.Idle -> {
                    Text("Seo oma Go3 konto telefoniga", color = Go3Colors.TextSecondary, fontSize = 22.sp)
                    Button(onClick = onStart) { Text("Alusta sidumist") }
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
                    Button(onClick = onStart) { Text("Loo uus kood") }
                }
                is DeviceAuthState.Failed -> {
                    Text(auth.message, color = Go3Colors.ErrorText, fontSize = 20.sp)
                    Button(onClick = onStart) { Text("Proovi uuesti") }
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
private fun PlayerScreen(state: TvUiState, player: Player) {
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
                selectedIndex = state.audioSettingsIndex,
                activeLanguage = state.audioLanguagePreference,
            )
            Overlay.SUBTITLE_SETTINGS -> LanguageSettingsOverlay(
                title = "SUBTIITRITE EELISTUS",
                description = "Kehtib kõigil kanalitel ja järelvaatamisel",
                options = SUBTITLE_LANGUAGE_OPTIONS,
                selectedIndex = state.subtitleSettingsIndex,
                activeLanguage = state.subtitleLanguagePreference,
            )
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
private fun SeekOverlay(state: TvUiState) {
    // While catchup plays, describe the catchup programme — not whatever is
    // currently airing live on the channel.
    val watching = state.catchupProgram
    val channel = state.channels.firstOrNull { it.id == (watching?.channelId ?: state.currentChannelId) }
    val playbackInstant = state.seekLiveOffsetMs
        ?.takeIf { state.seekIsLive }
        ?.let { Instant.now().minusMillis(it) }
        ?: Instant.now()
    val program = watching ?: state.programsFor(state.currentChannelId)
        .firstOrNull { ProgramWindow.isCurrent(it, playbackInstant) }
    val timelineStart = if (watching != null && !state.seekIsLive) {
        watching.startsAt
    } else {
        playbackInstant.minusMillis(state.seekPositionMs.coerceAtLeast(0L))
    }
    val programBoundaries = ProgramWindow.boundaryFractions(
        state.programsFor(channel?.id),
        timelineStart,
        state.seekDurationMs,
    )
    val progress = if (state.seekDurationMs > 0L) {
        (state.seekPositionMs.toFloat() / state.seekDurationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val liveLabel = when {
        !state.seekIsLive -> formatPlaybackDuration(state.seekDurationMs)
        (state.seekLiveOffsetMs ?: Long.MAX_VALUE) <= 5_000L -> "OTSE"
        else -> "−${formatPlaybackDuration(state.seekLiveOffsetMs ?: 0L)} OTSEST"
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
                    "${channel?.serverNumber ?: ""}  ${channel?.name.orEmpty()}" +
                        if (watching != null) "  •  ${formatDate(watching.startsAt)} ${formatTime(watching.startsAt)}" else "",
                    color = Go3Colors.Cyan,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.seekPlaying) "▶" else "Ⅱ",
                        color = if (state.seekPlaying) Go3Colors.Cyan else Color.White,
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
                Text(formatPlaybackDuration(state.seekPositionMs), color = Color.White, fontSize = 14.sp)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                    KeyHintRow("◀▶" to "30 s", "OK" to "esita/paus", "BACK" to "sulge")
                }
                Text(liveLabel, color = if (liveLabel == "OTSE") Go3Colors.Cyan else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ChannelRail(state: TvUiState) {
    val railChannels = if (state.favoritesOnly) {
        state.channels.filter { it.id in state.favoriteChannelIds }
    } else state.channels
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
                    Text("${channel.serverNumber ?: index + 1}", color = if (selected) Color.White else Go3Colors.TextFaint, fontSize = 18.sp, modifier = Modifier.width(42.dp))
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
    val guideChannels = if (state.favoritesOnly) {
        state.channels.filter { it.id in state.favoriteChannelIds }
    } else state.channels
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
                "${channel.serverNumber ?: channelIndex + 1}",
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
    val profileName = state.profiles.firstOrNull { it.id == state.selectedProfileId }?.name ?: "Praegune Go3 profiil"
    val rows = listOf(
        "Go3 profiil" to profileName,
        "Kanalid" to "Lemmikud, numbrid ja järjekord",
        "Helirada" to "${state.audioTrackLabel} • kõigil kanalitel",
        "Subtiitrid" to "${state.subtitleTrackLabel} • kõigil kanalitel",
        "Värskenda kanalipaketti" to "Kontrolli Go3 tellimust ja peidetud kanaleid uuesti",
    )
    Box(Modifier.fillMaxSize().background(Go3Colors.Scrim), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier.fillMaxHeight().width(760.dp)
                .background(Go3Brushes.settingsPanel)
                .padding(horizontal = 44.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OverlayHeader(
                "SEADED",
                "Go3 TV+",
                keyHints = listOf("▲▼" to "vali", "OK" to "ava/muuda", "BACK" to "sulge"),
            )
            Spacer(Modifier.height(8.dp))
            rows.forEachIndexed { index, (title, value) ->
                val selected = index == state.appSettingsIndex
                SettingsRow(selected, verticalPadding = 11.dp) {
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
                        Text(value, color = if (selected) Color.White else Go3Colors.TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("›", color = if (selected) Color.White else Go3Colors.TextFaint, fontSize = 30.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Text("Versioon ${ee.local.go3tvplus.BuildConfig.VERSION_NAME}", color = Go3Colors.TextFaint, fontSize = 13.sp)
        }
    }
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
            val selected = index == state.profileSettingsIndex
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
    val selectedIndex = state.settingsIndex.coerceIn(0, state.channels.lastIndex.coerceAtLeast(0))
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
                Text("${channel.serverNumber ?: index + 1}", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(70.dp))
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

private fun TvUiState.programsFor(channelId: String?): List<Program> =
    if (channelId == null) emptyList() else programsByChannel[channelId].orEmpty()

private fun List<Program>.nowProgram(): Program? {
    val now = Instant.now()
    return firstOrNull { ProgramWindow.isCurrent(it, now) }
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val dateFormatter = DateTimeFormatter.ofPattern("EEE, d. MMM", Locale.forLanguageTag("et-EE")).withZone(ZoneId.systemDefault())
private fun formatTime(instant: Instant): String = timeFormatter.format(instant)
private fun formatDate(instant: Instant): String = dateFormatter.format(instant).uppercase(Locale.forLanguageTag("et-EE"))

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
