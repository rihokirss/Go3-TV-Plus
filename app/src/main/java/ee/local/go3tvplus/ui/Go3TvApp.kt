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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
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

private val Accent = Color(0xFF178BFF)
private val Cyan = Color(0xFF32C7FF)
private val Panel = Color(0xF2071221)
private val SoftPanel = Color(0xE80B1B30)
private val SelectedRow = Color(0xD9193C67)
private val ProgramBlue = Color(0xE31D3550)
private val CurrentProgramBlue = Color(0xEB164E78)

@Composable
fun Go3TvApp(viewModel: TvViewModel, player: Player) {
    val state by viewModel.state.collectAsState()
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Color(0xFF050B14))) {
            when {
                state.auth != DeviceAuthState.Approved -> PairingScreen(state.auth, viewModel::startPairing)
                state.selectedProfileId == null && state.profiles.isEmpty() -> StartupScreen()
                state.selectedProfileId == null -> ProfileScreen(state.profiles, viewModel::selectProfile)
                else -> PlayerScreen(state, player, viewModel::retry, viewModel::clearError)
            }
            if (state.isDemo) DemoBadge()
        }
    }
}

@Composable
private fun StartupScreen() {
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Color(0xFF10365B), Color(0xFF050B14))),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text("Go3 TV+", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PairingScreen(auth: DeviceAuthState, onStart: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Color(0xFF10365B), Color(0xFF050B14))),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Go3 TV+", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            when (auth) {
                DeviceAuthState.Idle -> {
                    Text("Seo oma Go3 konto telefoniga", color = Color.LightGray, fontSize = 22.sp)
                    Button(onClick = onStart) { Text("Alusta sidumist") }
                }
                DeviceAuthState.RequestingCode -> Text("Loon sidumiskoodi…", color = Color.White, fontSize = 22.sp)
                is DeviceAuthState.AwaitingApproval -> {
                    val qr = remember(auth.qrPayload) { qrCode(auth.qrPayload) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Image(qr, "Go3 sidumise QR-kood", Modifier.size(250.dp).background(Color.White).padding(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Skänni QR-kood telefoniga", color = Color.White, fontSize = 25.sp)
                            Text("või ava", color = Color.Gray, fontSize = 18.sp)
                            Text(auth.verificationUrl, color = Color.LightGray, fontSize = 18.sp)
                            Text(auth.deviceCode, color = Accent, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                            Text("Ootan telefonis kinnitamist…", color = Color.LightGray, fontSize = 17.sp)
                        }
                    }
                }
                DeviceAuthState.Approved -> Unit
                DeviceAuthState.Expired -> {
                    Text("Sidumiskood aegus", color = Color.White, fontSize = 22.sp)
                    Button(onClick = onStart) { Text("Loo uus kood") }
                }
                is DeviceAuthState.Failed -> {
                    Text(auth.message, color = Color(0xFFFFA0A5), fontSize = 20.sp)
                    Button(onClick = onStart) { Text("Proovi uuesti") }
                }
            }
        }
    }
}

@Composable
private fun ProfileScreen(profiles: List<Profile>, onSelect: (Profile) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(30.dp)) {
            Text("Kes vaatab?", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            if (profiles.isEmpty()) {
                Text("Laadin profiile…", color = Color.LightGray, fontSize = 20.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    profiles.forEach { profile ->
                        Button(onClick = { onSelect(profile) }, modifier = Modifier.width(210.dp).height(86.dp)) {
                            Text(profile.name, fontSize = 20.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerScreen(state: TvUiState, player: Player, onRetry: () -> Unit, onDismissError: () -> Unit) {
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
                    .background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 28.dp, vertical = 16.dp),
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
                    .background(SoftPanel, RoundedCornerShape(18.dp))
                    .padding(horizontal = if (firstLoad) 20.dp else 14.dp, vertical = if (firstLoad) 20.dp else 8.dp),
                color = Color.White,
                fontSize = if (firstLoad) 20.sp else 14.sp,
            )
        }

        state.notice?.let { notice ->
            Text(
                notice,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 38.dp)
                    .background(Color(0xF0193655), RoundedCornerShape(18.dp))
                    .border(1.dp, Cyan.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 22.dp, vertical = 10.dp),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        state.error?.let { message ->
            ErrorBanner(message, state.errorActionIndex, onRetry, onDismissError)
        }
    }
}

@Composable
private fun PlaybackStartupBackdrop(loading: Boolean) {
    Box(
        Modifier.fillMaxSize().background(Color(0xFF020712)),
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
                    color = Color(0xFFB8D8F2),
                    fontSize = 17.sp,
                )
            }
        }
    }
}

@Composable
private fun SeekOverlay(state: TvUiState) {
    val channel = state.channels.firstOrNull { it.id == state.currentChannelId }
    val program = state.programsFor(state.currentChannelId).nowProgram()
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
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.verticalGradient(listOf(Color(0xED102C4A), Color(0xF2071221))))
                .padding(horizontal = 26.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${channel?.serverNumber ?: ""}  ${channel?.name.orEmpty()}",
                    color = Cyan,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(if (state.seekPlaying) "MÄNGIB" else "PAUS", color = Color(0xFFAFC4D8), fontSize = 13.sp)
            }
            Text(
                program?.title ?: "Ajanihe",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.fillMaxWidth().height(8.dp).background(Color(0xFF29445E), RoundedCornerShape(4.dp))) {
                if (progress > 0f) {
                    Box(
                        Modifier.fillMaxWidth(progress).fillMaxHeight()
                            .background(Brush.horizontalGradient(listOf(Accent, Cyan)), RoundedCornerShape(4.dp)),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formatPlaybackDuration(state.seekPositionMs), color = Color.White, fontSize = 14.sp)
                Text(
                    "← 30 s     → 30 s     •     OK esita/paus     •     BACK sulge",
                    color = Color(0xFF9FB5CA),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Text(liveLabel, color = if (liveLabel == "OTSE") Cyan else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
            .background(Brush.horizontalGradient(listOf(Panel, Color.Transparent)))
            .padding(horizontal = 32.dp, vertical = 38.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.favoritesOnly) "KANALID  •  LEMMIKUD" else "KANALID  •  KÕIK",
                    color = Accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (state.favoritesOnly) "←  ★ Lemmikud" else "←  ☆ Näita lemmikuid",
                    modifier = Modifier
                        .background(
                            if (state.favoritesOnly) Accent else Color(0xD117304B),
                            RoundedCornerShape(15.dp),
                        )
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = if (state.favoritesOnly) FontWeight.Bold else FontWeight.Normal,
                )
            }
            if (railChannels.isEmpty()) {
                Text("Lemmikkanaleid pole veel valitud", color = Color.White, fontSize = 18.sp)
                Text("Lisa lemmik täisekraanil ← seadistusest", color = Color.LightGray, fontSize = 14.sp)
            }
            visible.forEachIndexed { offset, channel ->
                val index = first + offset
                val selected = index == selectedIndex
                val now = state.programsFor(channel.id).nowProgram()
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (selected) Accent else Color.Transparent, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${channel.serverNumber ?: index + 1}", color = if (selected) Color.White else Color.Gray, fontSize = 18.sp, modifier = Modifier.width(42.dp))
                    Column {
                        Text(channel.name, color = Color.White, fontSize = 21.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        Text(now?.title ?: "Saatekava puudub", color = if (selected) Color.White else Color.LightGray, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            Text("→ Telekava   •   OK vali", color = Color.LightGray, fontSize = 14.sp)
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
    val windowStart = anchor.minus(Duration.ofMinutes(30))
    val windowEnd = windowStart.plus(Duration.ofHours(4))
    val visibleCount = 6
    val first = (state.guideChannelIndex - 2)
        .coerceIn(0, (guideChannels.size - visibleCount).coerceAtLeast(0))
    val visibleChannels = guideChannels.drop(first).take(visibleCount)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.82f)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(Brush.verticalGradient(listOf(Color(0xB80E2946), Color(0xC4071221)))),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(220.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("TELEKAVA", color = Cyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (state.favoritesOnly) "  ★ LEMMIKUD" else "  ☆ KÕIK",
                            color = if (state.favoritesOnly) Color(0xFFFFD65C) else Color(0xFF91A9C3),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(formatDate(anchor), color = Color(0xFF91A9C3), fontSize = 12.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        selectedChannel?.name.orEmpty(),
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(guideControlsHint(), color = Color(0xFF9BB0C7), fontSize = 12.sp)
                }
            }

            Row(Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("KANAL", color = Color(0xFF718AA5), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(206.dp))
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        repeat(5) { hour ->
                            Text(formatTime(windowStart.plus(Duration.ofHours(hour.toLong()))), color = Color(0xFF8FA6BE), fontSize = 12.sp)
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
                                .background(Color(0xF019567C), RoundedCornerShape(9.dp))
                                .border(1.dp, Cyan.copy(alpha = 0.75f), RoundedCornerShape(9.dp))
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
            }

            Row(
                Modifier.fillMaxWidth().height(62.dp)
                    .background(Color(0xE5122740), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectedProgram == null) {
                    Text("Selle kanali saatekava puudub", color = Color(0xFF9BB0C7), fontSize = 16.sp)
                } else {
                    Column(Modifier.width(112.dp)) {
                        Text("${formatTime(selectedProgram.startsAt)}–${formatTime(selectedProgram.endsAt)}", color = Cyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(formatDate(selectedProgram.startsAt), color = Color(0xFF8299B2), fontSize = 11.sp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(selectedProgram.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(selectedProgram.description.orEmpty(), color = Color(0xFFAAB9C9), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (selectedProgram.id in state.scheduledReminderIds) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = Color(0xFF59E391))) { append("●") }
                                append("  MEELDETULETUS")
                            },
                            modifier = Modifier.padding(start = 9.dp).background(Color(0xFF145A3B), RoundedCornerShape(12.dp)).padding(horizontal = 9.dp, vertical = 4.dp),
                            color = Color(0xFFC9FFE0),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (selectedProgram.id in state.scheduledAutoTuneIds) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = Color(0xFF55B4FF))) { append("●") }
                                append("  AUTOLÜLITUS")
                            },
                            modifier = Modifier.padding(start = 7.dp).background(Color(0xFF174E82), RoundedCornerShape(12.dp)).padding(horizontal = 9.dp, vertical = 4.dp),
                            color = Color(0xFFD5EAFF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    val status = when {
                        ProgramWindow.isCurrent(selectedProgram, now) -> "OTSE"
                        selectedProgram.endsAt.isBefore(now) && selectedProgram.catchupAvailable -> "JÄRELVAATAMINE"
                        selectedProgram.endsAt.isBefore(now) -> "LÕPPENUD"
                        else -> "TULEKUL"
                    }
                    Text(
                        status,
                        modifier = Modifier.padding(start = 14.dp).background(Color(0xFF173C60), RoundedCornerShape(14.dp)).padding(horizontal = 11.dp, vertical = 5.dp),
                        color = if (status == "OTSE") Cyan else Color(0xFFB9C7D6),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

private fun guideControlsHint() = buildAnnotatedString {
    append("←→ aeg  •  ↑↓ kanal  •  OK vaata  •  hoia OK lemmikud  •  ")
    withStyle(SpanStyle(color = Color(0xFFFFD84D))) { append("●") }
    append(" abi")
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
    val rowColor = if (selectedChannel) SelectedRow else Color(0x3D142A42)
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(rowColor),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.width(220.dp).padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${channel.serverNumber ?: channelIndex + 1}",
                modifier = Modifier.width(40.dp).background(if (selectedChannel) Accent else Color(0xFF17304B), RoundedCornerShape(6.dp)).padding(vertical = 4.dp),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                channel.name,
                modifier = Modifier.padding(start = 9.dp).weight(1f),
                color = if (selectedChannel) Color.White else Color(0xFFC1CEDB),
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
            modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(7.dp)).background(Color(0xB5091829)),
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
        val verticalPadding = 3.dp.toPx()
        val horizontalPadding = 8.dp.toPx()
        val gap = 3.dp.toPx()
        val minimumWidth = 30.dp.toPx()
        val radius = 6.dp.toPx()
        timePaint.textSize = 11.sp.toPx()
        titlePaint.textSize = 13.sp.toPx()
        borderPaint.strokeWidth = 2.dp.toPx()
        borderPaint.color = Color(0xFFDDF4FF).toArgb()
        linePaint.color = Cyan.copy(alpha = 0.32f).toArgb()
        progressTrackPaint.color = Color(0xA0061728).toArgb()
        progressPaint.color = Cyan.toArgb()
        actionHaloPaint.color = Color(0xCC06121F).toArgb()
        reminderPaint.color = Color(0xFF59E391).toArgb()
        autoTunePaint.color = Color(0xFF55B4FF).toArgb()

        // Keep the current-time marker behind programme cards. It remains visible
        // in the narrow gaps without ever crossing programme titles.
        if (!now.isBefore(windowStart) && now.isBefore(windowEnd)) {
            val nowFraction = (Duration.between(windowStart, now).toMillis() / totalMillis).coerceIn(0f, 1f)
            val lineX = size.width * nowFraction
            canvas.drawRect(lineX, 0f, lineX + 1.dp.toPx(), size.height, linePaint)
        }

        drawingPrograms.forEach { program ->
                val clippedStart = if (program.startsAt.isBefore(windowStart)) windowStart else program.startsAt
                val clippedEnd = if (program.endsAt.isAfter(windowEnd)) windowEnd else program.endsAt
                val startFraction = (Duration.between(windowStart, clippedStart).toMillis() / totalMillis).coerceIn(0f, 1f)
                val endFraction = (Duration.between(windowStart, clippedEnd).toMillis() / totalMillis).coerceIn(0f, 1f)
                val left = size.width * startFraction
                val naturalRight = size.width * endFraction - gap
                val right = naturalRight.coerceAtLeast(left + minimumWidth).coerceAtMost(size.width)
                if (right <= left) return@forEach
                val selected = program.id == selectedProgramId
                fillPaint.color = when {
                    selected -> Accent.toArgb()
                    ProgramWindow.isCurrent(program, now) -> CurrentProgramBlue.toArgb()
                    else -> ProgramBlue.toArgb()
                }
                val rect = RectF(left, verticalPadding, right, size.height - verticalPadding)
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
                if (selected) canvas.drawRoundRect(rect, radius, radius, borderPaint)

                val availableTextWidth = (right - left - horizontalPadding * 2).coerceAtLeast(1f)
                timePaint.color = if (selected) Color.White.toArgb() else Color(0xFFA7BDD2).toArgb()
                titlePaint.color = Color.White.toArgb()
                val timeBaseline = verticalPadding + timePaint.textSize + 2.dp.toPx()
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
            val right = (size.width * endFraction - gap)
                .coerceAtLeast(left + minimumWidth)
                .coerceAtMost(size.width)
            if (right <= left) return@forEach

            val dotRadius = 3.5.dp.toPx()
            val haloRadius = dotRadius + 1.5.dp.toPx()
            val dotGap = 3.dp.toPx()
            val dotY = verticalPadding + 7.dp.toPx()
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
    Box(Modifier.fillMaxSize().background(Color(0xB0050B14)), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier.fillMaxHeight().width(760.dp)
                .background(Brush.horizontalGradient(listOf(Color(0xFC071A2D), Color(0xF20E2946))))
                .padding(horizontal = 44.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("SEADED", color = Cyan, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Go3 TV+", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text("↑↓ vali  •  OK või → ava/muuda  •  BACK sulge", color = Color(0xFF9DB2C7), fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            rows.forEachIndexed { index, (title, value) ->
                val selected = index == state.appSettingsIndex
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (selected) Accent else Color(0xB3132942), RoundedCornerShape(10.dp))
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
                        Text(value, color = if (selected) Color.White else Color(0xFFAABBCD), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("›", color = if (selected) Color.White else Color(0xFF7890A8), fontSize = 30.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Text("Versioon ${ee.local.go3tvplus.BuildConfig.VERSION_NAME}", color = Color(0xFF71879E), fontSize = 13.sp)
        }
    }
}

@Composable
private fun ProfileSettingsOverlay(state: TvUiState) {
    Box(Modifier.fillMaxSize().background(Color(0xE6051020)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(680.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("GO3 PROFIIL", color = Cyan, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Vali vaatamisprofiil", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Profiili vahetamine värskendab kanalipaketti", color = Color(0xFF9DB2C7), fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            if (state.profiles.isEmpty()) {
                Text("Laadin Go3 profiile…", color = Color.LightGray, fontSize = 18.sp)
            }
            state.profiles.forEachIndexed { index, profile ->
                val selected = index == state.profileSettingsIndex
                val active = profile.id == state.selectedProfileId
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (selected) Accent else Color(0xD1132942), RoundedCornerShape(10.dp))
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(profile.name, color = Color.White, fontSize = 21.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    if (active) Text("PRAEGUNE", color = if (selected) Color.White else Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text("↑↓ vali  •  OK kinnita  •  BACK tagasi", color = Color(0xFF8FA4BA), fontSize = 14.sp, modifier = Modifier.align(Alignment.End))
        }
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
    Box(Modifier.fillMaxSize().background(Color(0xE6051020)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(680.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = Cyan, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(description, color = Color(0xFF9DB2C7), fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            options.forEachIndexed { index, (language, label) ->
                val selected = index == selectedIndex
                val active = language == activeLanguage
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (selected) Accent else Color(0xD1132942), RoundedCornerShape(10.dp))
                        .padding(horizontal = 18.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, color = Color.White, fontSize = 21.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    if (active) Text("EELISTATUD", color = if (selected) Color.White else Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text("↑↓ vali  •  OK kinnita  •  BACK tagasi", color = Color(0xFF8FA4BA), fontSize = 14.sp, modifier = Modifier.align(Alignment.End))
        }
    }
}

@Composable
private fun ChannelSettingsOverlay(state: TvUiState) {
    val selectedIndex = state.settingsIndex.coerceIn(0, state.channels.lastIndex.coerceAtLeast(0))
    val first = (selectedIndex - 3).coerceAtLeast(0)
    val visible = state.channels.drop(first).take(7)
    Box(Modifier.fillMaxSize().background(Color(0xE6051020)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(700.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("KANALITE SEADISTUS", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("↑↓ vali  •  ←→ muuda numbrit  •  OK lemmik  •  numbriklahvid sisestavad uue numbri", color = Color.LightGray, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            visible.forEachIndexed { offset, channel ->
                val index = first + offset
                val selected = index == selectedIndex
                Row(
                    Modifier.fillMaxWidth().background(if (selected) Accent else Color(0xD1132942), RoundedCornerShape(7.dp)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${channel.serverNumber ?: index + 1}", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(70.dp))
                    Text(channel.name, color = Color.White, fontSize = 20.sp, modifier = Modifier.weight(1f))
                    Text(if (channel.id in state.favoriteChannelIds) "★ Lemmik" else "☆", color = if (channel.id in state.favoriteChannelIds) Color(0xFFFFD45A) else Color.LightGray, fontSize = 18.sp)
                }
            }
            Text("BACK sulgeb", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.align(Alignment.End))
        }
    }
}

@Composable
private fun ErrorBanner(message: String, selectedAction: Int, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xEE651F28)).padding(horizontal = 30.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(message, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
        ErrorAction("Proovi uuesti", selectedAction == 0, onRetry)
        ErrorAction("Sulge", selectedAction == 1, onDismiss)
    }
}

@Composable
private fun ErrorAction(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.then(
            if (selected) Modifier.border(3.dp, Color.White, RoundedCornerShape(24.dp)) else Modifier,
        ),
    ) {
        Text(if (selected) "› $label" else label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun DemoBadge() {
    Text(
        "DEMO – Go3 API pole ühendatud",
        modifier = Modifier.padding(14.dp).background(Color(0xE315426B), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 7.dp),
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
