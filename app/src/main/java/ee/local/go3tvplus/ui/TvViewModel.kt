package ee.local.go3tvplus.ui

import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import ee.local.go3tvplus.AppContainer
import ee.local.go3tvplus.data.AuthCoordinator
import ee.local.go3tvplus.data.TvRepository
import ee.local.go3tvplus.data.local.ChannelPreference
import ee.local.go3tvplus.data.local.ScheduledProgramAction
import ee.local.go3tvplus.domain.Channel
import ee.local.go3tvplus.domain.DeviceAuthState
import ee.local.go3tvplus.domain.Go3Failure
import ee.local.go3tvplus.domain.PlaybackTicket
import ee.local.go3tvplus.domain.Profile
import ee.local.go3tvplus.domain.Program
import ee.local.go3tvplus.player.TvPlayer
import ee.local.go3tvplus.player.SeekSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

enum class Overlay {
    NONE, CHANNEL_RAIL, GUIDE, APP_SETTINGS, CHANNEL_SETTINGS, PROFILE_SETTINGS,
    AUDIO_SETTINGS, SUBTITLE_SETTINGS, SEEK,
}

data class TvUiState(
    val auth: DeviceAuthState = DeviceAuthState.Idle,
    val profiles: List<Profile> = emptyList(),
    val selectedProfileId: String? = null,
    val channels: List<Channel> = emptyList(),
    val programsByChannel: Map<String, List<Program>> = emptyMap(),
    val currentChannelId: String? = null,
    val overlay: Overlay = Overlay.NONE,
    val settingsReturnOverlay: Overlay = Overlay.NONE,
    val railIndex: Int = 0,
    /** One shared filter keeps the channel rail and guide in the same mode. */
    val favoritesOnly: Boolean = false,
    val guideChannelIndex: Int = 0,
    val guideProgramIndex: Int = 0,
    val guideAnchor: Instant? = null,
    val settingsIndex: Int = 0,
    val appSettingsIndex: Int = 0,
    val profileSettingsIndex: Int = 0,
    val audioSettingsIndex: Int = 0,
    val subtitleSettingsIndex: Int = 0,
    val audioLanguagePreference: String = "et",
    val subtitleLanguagePreference: String? = null,
    val audioTrackLabel: String = "Eesti",
    val subtitleTrackLabel: String = "Väljas",
    val favoriteChannelIds: Set<String> = emptySet(),
    val numberInput: String = "",
    val seekPositionMs: Long = 0L,
    val seekDurationMs: Long = 0L,
    val seekLiveOffsetMs: Long? = null,
    val seekIsLive: Boolean = false,
    val seekPlaying: Boolean = true,
    val loading: Boolean = false,
    val videoVisible: Boolean = false,
    val error: String? = null,
    val errorActionIndex: Int = 0,
    val scheduledReminderIds: Set<String> = emptySet(),
    val scheduledAutoTuneIds: Set<String> = emptySet(),
    val notice: String? = null,
    val isDemo: Boolean = false,
)

class TvViewModel(
    private val authCoordinator: AuthCoordinator,
    private val repository: TvRepository,
    private val tvPlayer: TvPlayer,
    isDemo: Boolean,
) : ViewModel(), TvPlayer.Listener {
    val mediaPlayer get() = tvPlayer.player
    private val mutableState = MutableStateFlow(TvUiState(isDemo = isDemo))
    val state: StateFlow<TvUiState> = mutableState.asStateFlow()
    private var digitJob: Job? = null
    private var heldDigitKey: Int? = null
    private var guideOkJob: Job? = null
    private var guideLongPressHandled = false
    private var railJob: Job? = null
    private var tuneJob: Job? = null
    private var prolongJob: Job? = null
    private var seekUiJob: Job? = null
    private var seekCloseJob: Job? = null
    private var playbackHealthJob: Job? = null
    private var playbackRetryJob: Job? = null
    private var programActionJob: Job? = null
    private var noticeJob: Job? = null
    private var activeTicket: PlaybackTicket? = null
    private var pendingChannelId: String? = null
    private var retryCount = 0
    private var wasBackgrounded = false
    private var manuallyTimeShifted = false
    private var scheduledProgramActions: Map<String, ScheduledProgramAction> = emptyMap()
    private val shownReminderIds = mutableSetOf<String>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        tvPlayer.setListener(this)
        viewModelScope.launch {
            val playbackPreferences = repository.playbackPreferences()
            tvPlayer.applyTrackPreferences(
                playbackPreferences.audioLanguage,
                playbackPreferences.subtitleLanguage,
            )
            mutableState.value = mutableState.value.copy(
                audioLanguagePreference = playbackPreferences.audioLanguage,
                subtitleLanguagePreference = playbackPreferences.subtitleLanguage,
                audioTrackLabel = tvPlayer.audioTrackLabel(),
                subtitleTrackLabel = tvPlayer.subtitleTrackLabel(),
            )
        }
        programActionJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            scheduledProgramActions = repository.scheduledProgramActions()
                .filter { it.startsAtEpochMs >= now - PROGRAM_ACTION_GRACE_MS }
                .associateBy(ScheduledProgramAction::programId)
            publishScheduledProgramActions()
            repository.saveScheduledProgramActions(scheduledProgramActions.values)
            runProgramActionScheduler()
        }
        viewModelScope.launch {
            authCoordinator.state.collect { auth ->
                mutableState.value = mutableState.value.copy(auth = auth, error = null)
                if (auth == DeviceAuthState.Approved) restoreProfileOrLoadProfiles()
            }
        }
        viewModelScope.launch {
            repository.guide.collect { (rawChannels, programs) ->
                val profileId = mutableState.value.selectedProfileId
                val hiddenChannelIds = profileId?.let { repository.hiddenChannelIds(it) }.orEmpty()
                val availableRawChannels = rawChannels.filterNot { it.id in hiddenChannelIds }
                val availableIds = availableRawChannels.mapTo(mutableSetOf(), Channel::id)
                val indexedPrograms = withContext(Dispatchers.Default) {
                    programs.groupBy(Program::channelId)
                        .filterKeys { it in availableIds }
                        .mapValues { (_, channelPrograms) -> channelPrograms.sortedBy(Program::startsAt) }
                }
                val saved = repository.channelPreferences(availableRawChannels)
                val channels = availableRawChannels.mapIndexed { index, channel ->
                    channel.copy(serverNumber = saved[channel.id]?.number ?: channel.serverNumber ?: index + 1)
                }.sortedBy { it.serverNumber }
                val favoriteIds = saved.values.filter(ChannelPreference::favorite)
                    .map(ChannelPreference::channelId).filterTo(mutableSetOf()) { it in availableIds }
                mutableState.value = mutableState.value.copy(
                    channels = channels,
                    programsByChannel = indexedPrograms,
                    favoriteChannelIds = favoriteIds,
                    favoritesOnly = mutableState.value.favoritesOnly && favoriteIds.isNotEmpty(),
                )
                if (channels.isNotEmpty() && mutableState.value.currentChannelId == null) {
                    val preferred = repository.lastChannelId()
                    val favorites = saved.values.filter(ChannelPreference::favorite).map(ChannelPreference::channelId).toSet()
                    tune(channels.firstOrNull { it.id == preferred } ?: channels.firstOrNull { it.id in favorites } ?: channels.first())
                }
            }
        }
    }

    fun startPairing() = authCoordinator.start()

    fun selectProfile(profile: Profile) {
        viewModelScope.launch {
            repository.saveSelectedProfile(profile.id)
            val firstLoad = mutableState.value.channels.isEmpty()
            mutableState.value = mutableState.value.copy(selectedProfileId = profile.id, loading = firstLoad)
            runCatching { repository.refresh(profile.id) }
                .onFailure { if (mutableState.value.channels.isEmpty()) showError(it) }
            mutableState.value = mutableState.value.copy(loading = false)
        }
    }

    private suspend fun restoreProfileOrLoadProfiles() {
        val remembered = repository.selectedProfileId()
        if (remembered == null) {
            loadProfiles()
            return
        }
        val firstLoad = mutableState.value.channels.isEmpty()
        mutableState.value = mutableState.value.copy(selectedProfileId = remembered, loading = firstLoad)
        try {
            repository.refresh(remembered)
        } catch (error: Exception) {
            if (mutableState.value.channels.isEmpty()) showError(error)
        } finally {
            mutableState.value = mutableState.value.copy(loading = false)
        }
    }

    private suspend fun loadProfiles() {
        mutableState.value = mutableState.value.copy(loading = true)
        try {
            val profiles = repository.profiles()
            val remembered = repository.selectedProfileId()
            val selected = profiles.firstOrNull { it.id == remembered }
                ?: profiles.singleOrNull()
            if (selected != null && selected.id != remembered) {
                repository.saveSelectedProfile(selected.id)
            }
            mutableState.value = mutableState.value.copy(
                profiles = profiles,
                selectedProfileId = selected?.id,
                loading = false,
            )
            if (selected != null) repository.refresh(selected.id)
        } catch (error: Exception) {
            showError(error)
            mutableState.value = mutableState.value.copy(loading = false)
        }
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (guideLongPressHandled && event.keyCode.isConfirmKey()) {
            if (event.action == KeyEvent.ACTION_UP) {
                guideLongPressHandled = false
                guideOkJob = null
            }
            return true
        }
        val snapshot = mutableState.value
        if (snapshot.error != null) {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    mutableState.value = snapshot.copy(errorActionIndex = 0)
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    mutableState.value = snapshot.copy(errorActionIndex = 1)
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (snapshot.errorActionIndex == 0) retry() else clearError()
                    true
                }
                KeyEvent.KEYCODE_BACK -> {
                    clearError()
                    true
                }
                else -> true
            }
        }
        if (snapshot.overlay == Overlay.GUIDE && event.keyCode.isConfirmKey()) {
            return handleGuideConfirm(event)
        }
        if (snapshot.overlay == Overlay.GUIDE && event.keyCode in PROGRAM_COLOR_KEYS) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                handleGuideColorKey(event.keyCode)
            }
            return true
        }
        if (event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 || event.keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) {
            if (event.action == KeyEvent.ACTION_UP) {
                heldDigitKey = null
                return true
            }
            if (event.action != KeyEvent.ACTION_DOWN) return true
            val digit = if (event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                event.keyCode - KeyEvent.KEYCODE_0
            } else {
                event.keyCode - KeyEvent.KEYCODE_NUMPAD_0
            }
            if (event.repeatCount == 0) {
                if (snapshot.overlay == Overlay.CHANNEL_SETTINGS) appendSettingsDigit(digit) else appendDigit(digit)
            } else if (snapshot.overlay != Overlay.CHANNEL_SETTINGS && heldDigitKey != event.keyCode) {
                heldDigitKey = event.keyCode
                digitJob?.cancel()
                mutableState.value = mutableState.value.copy(numberInput = "")
                tuneHeldNumber(digit)
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (snapshot.overlay != Overlay.NONE || snapshot.numberInput.isNotEmpty()) {
                if (snapshot.overlay == Overlay.SEEK) {
                    seekUiJob?.cancel()
                    seekCloseJob?.cancel()
                }
                val returnOverlay = if (
                    snapshot.overlay == Overlay.CHANNEL_SETTINGS || snapshot.overlay == Overlay.PROFILE_SETTINGS ||
                    snapshot.overlay == Overlay.AUDIO_SETTINGS || snapshot.overlay == Overlay.SUBTITLE_SETTINGS
                ) snapshot.settingsReturnOverlay else Overlay.NONE
                mutableState.value = snapshot.copy(overlay = returnOverlay, numberInput = "")
                return true
            }
            return false
        }
        if (event.keyCode == KeyEvent.KEYCODE_SETTINGS ||
            ((event.keyCode == KeyEvent.KEYCODE_GUIDE || event.keyCode == KeyEvent.KEYCODE_MENU) && event.repeatCount == 1)
        ) {
            openAppSettings()
            return true
        }
        if ((event.keyCode == KeyEvent.KEYCODE_GUIDE || event.keyCode == KeyEvent.KEYCODE_MENU) && event.repeatCount == 0) {
            toggleGuide()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP || event.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            channelStep(if (event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP) 1 else -1, immediate = true)
            return true
        }
        return when (snapshot.overlay) {
            Overlay.GUIDE -> handleGuideKey(event.keyCode)
            Overlay.CHANNEL_RAIL -> handleRailKey(event.keyCode)
            Overlay.APP_SETTINGS -> handleAppSettingsKey(event.keyCode)
            Overlay.CHANNEL_SETTINGS -> handleChannelSettingsKey(event.keyCode)
            Overlay.PROFILE_SETTINGS -> handleProfileSettingsKey(event.keyCode)
            Overlay.AUDIO_SETTINGS -> handleAudioSettingsKey(event.keyCode)
            Overlay.SUBTITLE_SETTINGS -> handleSubtitleSettingsKey(event.keyCode)
            Overlay.SEEK -> handleSeekKey(event.keyCode)
            Overlay.NONE -> handlePlayerKey(event.keyCode)
        }
    }

    private fun handlePlayerKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
            showRail()
            channelStep(if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1, immediate = false)
            true
        }
        KeyEvent.KEYCODE_DPAD_RIGHT -> {
            toggleGuide()
            true
        }
        KeyEvent.KEYCODE_DPAD_LEFT -> {
            openAppSettings()
            true
        }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
            openSeekOverlay()
            true
        }
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
            tvPlayer.togglePlayPause()
            true
        }
        else -> false
    }

    private fun openSeekOverlay() {
        updateSeekState(tvPlayer.seekSnapshot(), Overlay.SEEK)
        scheduleSeekClose()
        seekUiJob?.cancel()
        seekUiJob = viewModelScope.launch {
            while (mutableState.value.overlay == Overlay.SEEK) {
                delay(500)
                updateSeekState(tvPlayer.seekSnapshot(), Overlay.SEEK)
            }
        }
    }

    private fun handleSeekKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
            val next = tvPlayer.seekBy(-30_000L)
            if (next.isLive) manuallyTimeShifted = true
            updateSeekState(next, Overlay.SEEK)
            scheduleSeekClose()
            true
        }
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
            val before = tvPlayer.seekSnapshot()
            val next = tvPlayer.seekBy(30_000L)
            if (before.isLive) {
                manuallyTimeShifted = (next.liveOffsetMs ?: Long.MAX_VALUE) > 5_000L
            }
            updateSeekState(next, Overlay.SEEK)
            scheduleSeekClose()
            true
        }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
            tvPlayer.togglePlayPause()
            updateSeekState(tvPlayer.seekSnapshot(), Overlay.SEEK)
            scheduleSeekClose()
            true
        }
        else -> false
    }

    private fun updateSeekState(snapshot: SeekSnapshot, overlay: Overlay? = null) {
        mutableState.value = mutableState.value.copy(
            overlay = overlay ?: mutableState.value.overlay,
            seekPositionMs = snapshot.positionMs,
            seekDurationMs = snapshot.durationMs,
            seekLiveOffsetMs = snapshot.liveOffsetMs,
            seekIsLive = snapshot.isLive,
            seekPlaying = snapshot.isPlaying,
        )
    }

    private fun scheduleSeekClose() {
        seekCloseJob?.cancel()
        seekCloseJob = viewModelScope.launch {
            delay(SEEK_OVERLAY_TIMEOUT_MS)
            if (mutableState.value.overlay == Overlay.SEEK) {
                mutableState.value = mutableState.value.copy(overlay = Overlay.NONE)
                seekUiJob?.cancel()
            }
        }
    }

    private fun handleRailKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> { channelStep(-1, false); true }
        KeyEvent.KEYCODE_DPAD_DOWN -> { channelStep(1, false); true }
        KeyEvent.KEYCODE_DPAD_LEFT -> {
            toggleRailFavorites()
            true
        }
        KeyEvent.KEYCODE_DPAD_RIGHT -> {
            toggleGuide()
            true
        }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
            railChannels(mutableState.value).getOrNull(mutableState.value.railIndex)?.let(::tune)
            mutableState.value = mutableState.value.copy(overlay = Overlay.NONE)
            true
        }
        else -> false
    }

    private fun handleGuideKey(keyCode: Int): Boolean {
        val snapshot = mutableState.value
        val channels = guideChannels(snapshot)
        if (channels.isEmpty()) return true
        val channelPrograms = programsForGuideChannel(snapshot)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                val next = (snapshot.guideChannelIndex - 1).coerceAtLeast(0)
                mutableState.value = snapshot.copy(
                    guideChannelIndex = next,
                    guideProgramIndex = guideProgramIndexAt(channels, next, snapshot.guideAnchor ?: Instant.now()),
                )
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val next = (snapshot.guideChannelIndex + 1).coerceAtMost(channels.lastIndex)
                mutableState.value = snapshot.copy(
                    guideChannelIndex = next,
                    guideProgramIndex = guideProgramIndexAt(channels, next, snapshot.guideAnchor ?: Instant.now()),
                )
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val next = (snapshot.guideProgramIndex - 1).coerceAtLeast(0)
                mutableState.value = snapshot.copy(
                    guideProgramIndex = next,
                    guideAnchor = channelPrograms.getOrNull(next)?.startsAt ?: snapshot.guideAnchor,
                )
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val next = (snapshot.guideProgramIndex + 1).coerceAtMost(channelPrograms.lastIndex.coerceAtLeast(0))
                mutableState.value = snapshot.copy(
                    guideProgramIndex = next,
                    guideAnchor = channelPrograms.getOrNull(next)?.startsAt ?: snapshot.guideAnchor,
                )
            }
            else -> return false
        }
        return true
    }

    private fun handleGuideColorKey(keyCode: Int) {
        if (keyCode == KeyEvent.KEYCODE_PROG_YELLOW) {
            showNotice("ROHELINE meeldetuletus  •  SININE automaatlülitus  •  PUNANE eemalda")
            return
        }
        val snapshot = mutableState.value
        val program = programsForGuideChannel(snapshot).getOrNull(snapshot.guideProgramIndex)
        if (program == null || !program.startsAt.isAfter(Instant.now())) {
            showNotice("Meeldetuletuse saab lisada tulevasele saatele")
            return
        }
        val previous = scheduledProgramActions[program.id]
        val updated = when (keyCode) {
            KeyEvent.KEYCODE_PROG_GREEN -> ScheduledProgramAction(
                programId = program.id,
                channelId = program.channelId,
                startsAtEpochMs = program.startsAt.toEpochMilli(),
                reminder = previous?.reminder != true,
                autoTune = previous?.autoTune == true,
            )
            KeyEvent.KEYCODE_PROG_BLUE -> ScheduledProgramAction(
                programId = program.id,
                channelId = program.channelId,
                startsAtEpochMs = program.startsAt.toEpochMilli(),
                reminder = previous?.reminder == true,
                autoTune = previous?.autoTune != true,
            )
            KeyEvent.KEYCODE_PROG_RED -> null
            else -> return
        }?.takeIf { it.reminder || it.autoTune }

        scheduledProgramActions = scheduledProgramActions.toMutableMap().apply {
            if (updated == null) remove(program.id) else put(program.id, updated)
        }
        shownReminderIds.remove(program.id)
        publishScheduledProgramActions()
        viewModelScope.launch { repository.saveScheduledProgramActions(scheduledProgramActions.values) }
        val message = when {
            updated == null -> "${program.title}: toiming eemaldatud"
            updated.reminder && updated.autoTune -> "${program.title}: meeldetuletus ja automaatlülitus"
            updated.autoTune -> "${program.title}: automaatlülitus"
            else -> "${program.title}: meeldetuletus"
        }
        showNotice(message)
    }

    private fun publishScheduledProgramActions() {
        mutableState.value = mutableState.value.copy(
            scheduledReminderIds = scheduledProgramActions.values
                .filter(ScheduledProgramAction::reminder)
                .mapTo(mutableSetOf(), ScheduledProgramAction::programId),
            scheduledAutoTuneIds = scheduledProgramActions.values
                .filter(ScheduledProgramAction::autoTune)
                .mapTo(mutableSetOf(), ScheduledProgramAction::programId),
        )
    }

    private suspend fun runProgramActionScheduler() {
        while (true) {
            val now = System.currentTimeMillis()
            val snapshot = mutableState.value
            val dueReminders = scheduledProgramActions.values.filter {
                it.reminder && it.programId !in shownReminderIds &&
                    now >= it.startsAtEpochMs - PROGRAM_REMINDER_LEAD_MS && now < it.startsAtEpochMs
            }
            if (!wasBackgrounded) {
                dueReminders.forEach { action ->
                    shownReminderIds += action.programId
                    val program = snapshot.programsByChannel[action.channelId]
                        .orEmpty().firstOrNull { it.id == action.programId }
                    showNotice("${program?.title ?: "Saade"} algab ühe minuti pärast")
                }
            }

            val dueActions = scheduledProgramActions.values.filter {
                now >= it.startsAtEpochMs && now <= it.startsAtEpochMs + PROGRAM_ACTION_GRACE_MS
            }
            if (!wasBackgrounded && dueActions.isNotEmpty()) {
                dueActions.forEach { action ->
                    val currentState = mutableState.value
                    val program = currentState.programsByChannel[action.channelId]
                        .orEmpty().firstOrNull { it.id == action.programId }
                    if (action.autoTune) {
                        currentState.channels.firstOrNull { it.id == action.channelId }?.let(::tune)
                    }
                    showNotice(
                        if (action.autoTune) "${program?.title ?: "Saade"} algas — lülitan kanalile"
                        else "${program?.title ?: "Saade"} algas",
                    )
                }
                val completedIds = dueActions.mapTo(mutableSetOf(), ScheduledProgramAction::programId)
                scheduledProgramActions = scheduledProgramActions - completedIds
                shownReminderIds.removeAll(completedIds)
                publishScheduledProgramActions()
                repository.saveScheduledProgramActions(scheduledProgramActions.values)
            }

            val expiredIds = scheduledProgramActions.values
                .filter { now > it.startsAtEpochMs + PROGRAM_ACTION_GRACE_MS }
                .mapTo(mutableSetOf(), ScheduledProgramAction::programId)
            if (expiredIds.isNotEmpty()) {
                scheduledProgramActions = scheduledProgramActions - expiredIds
                shownReminderIds.removeAll(expiredIds)
                publishScheduledProgramActions()
                repository.saveScheduledProgramActions(scheduledProgramActions.values)
            }
            delay(PROGRAM_ACTION_POLL_MS)
        }
    }

    private fun showNotice(message: String) {
        mutableState.value = mutableState.value.copy(notice = message)
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(PROGRAM_NOTICE_TIMEOUT_MS)
            if (mutableState.value.notice == message) {
                mutableState.value = mutableState.value.copy(notice = null)
            }
        }
    }

    private fun handleGuideConfirm(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    guideLongPressHandled = false
                    guideOkJob?.cancel()
                    guideOkJob = viewModelScope.launch {
                        delay(650)
                        toggleGuideFavorites()
                    }
                } else if (event.isLongPress || event.repeatCount > 0) {
                    toggleGuideFavorites()
                }
            }
            KeyEvent.ACTION_UP -> {
                guideOkJob?.cancel()
                guideOkJob = null
                if (!guideLongPressHandled) activateGuideSelection()
                guideLongPressHandled = false
            }
        }
        return true
    }

    private fun toggleGuideFavorites() {
        if (guideLongPressHandled) return
        guideOkJob?.cancel()
        guideLongPressHandled = true
        val snapshot = mutableState.value
        val favoritesOnly = !snapshot.favoritesOnly
        val targetChannels = if (favoritesOnly) {
            snapshot.channels.filter { it.id in snapshot.favoriteChannelIds }
        } else snapshot.channels
        if (targetChannels.isEmpty()) return
        val selectedId = guideChannels(snapshot).getOrNull(snapshot.guideChannelIndex)?.id
        val targetIndex = targetChannels.indexOfFirst { it.id == selectedId }
            .takeIf { it >= 0 }
            ?: targetChannels.indexOfFirst { it.id == snapshot.currentChannelId }.coerceAtLeast(0)
        mutableState.value = snapshot.copy(
            favoritesOnly = favoritesOnly,
            guideChannelIndex = targetIndex,
            guideProgramIndex = guideProgramIndexAt(
                targetChannels,
                targetIndex,
                snapshot.guideAnchor ?: Instant.now(),
            ),
        )
    }

    private fun activateGuideSelection() {
        val snapshot = mutableState.value
        val program = programsForGuideChannel(snapshot).getOrNull(snapshot.guideProgramIndex)
        val channel = guideChannels(snapshot).getOrNull(snapshot.guideChannelIndex)
        if (program != null && program.endsAt.isBefore(Instant.now())) playCatchup(program)
        else if (channel != null && (program == null || program.startsAt.isBefore(Instant.now()))) {
            mutableState.value = snapshot.copy(overlay = Overlay.NONE)
            tune(channel)
        }
        else if (program != null) mutableState.value = snapshot.copy(error = program.description ?: "Tulevane saade")
    }

    private fun programsForGuideChannel(snapshot: TvUiState): List<Program> {
        val channelId = guideChannels(snapshot).getOrNull(snapshot.guideChannelIndex)?.id ?: return emptyList()
        return snapshot.programsByChannel[channelId].orEmpty()
    }

    private fun guideChannels(snapshot: TvUiState): List<Channel> =
        if (snapshot.favoritesOnly) snapshot.channels.filter { it.id in snapshot.favoriteChannelIds }
        else snapshot.channels

    private fun appendDigit(number: Int) {
        if (number !in 0..9) return
        val previous = mutableState.value.numberInput
        val next = (if (previous.length >= 3) "" else previous) + number
        mutableState.value = mutableState.value.copy(numberInput = next, overlay = Overlay.NONE)
        digitJob?.cancel()
        digitJob = viewModelScope.launch {
            delay(2_000)
            tuneNumber()
        }
    }

    private fun appendSettingsDigit(number: Int) {
        val previous = mutableState.value.numberInput
        val next = (if (previous.length >= 3) "" else previous) + number
        mutableState.value = mutableState.value.copy(numberInput = next)
        digitJob?.cancel()
        digitJob = viewModelScope.launch {
            delay(2_000)
            commitSettingsNumber()
        }
    }

    private fun tuneHeldNumber(number: Int) {
        ChannelNumberResolver.resolve(mutableState.value.channels, number)
            ?.let(::tune)
            ?: run { mutableState.value = mutableState.value.copy(error = "Kanalit $number ei leitud") }
    }

    private fun commitSettingsNumber() {
        val snapshot = mutableState.value
        val channel = snapshot.channels.getOrNull(snapshot.settingsIndex) ?: return
        val number = snapshot.numberInput.toIntOrNull()
        mutableState.value = snapshot.copy(numberInput = "")
        val existing = snapshot.channels.associate { it.id to (it.serverNumber ?: snapshot.channels.indexOf(it) + 1) }
        if (number == null || number !in 1..999) {
            mutableState.value = mutableState.value.copy(error = "Kanalinumber peab olema vahemikus 1–999")
            return
        }
        assignChannelNumber(channel, number, existing)
    }

    private fun tuneNumber() {
        val number = mutableState.value.numberInput.toIntOrNull()
        mutableState.value = mutableState.value.copy(numberInput = "")
        ChannelNumberResolver.resolve(mutableState.value.channels, number)
            ?.let(::tune)
            ?: run { mutableState.value = mutableState.value.copy(error = "Kanalit $number ei leitud") }
    }

    private fun showRail() {
        val visibleChannels = railChannels(mutableState.value)
        val current = visibleChannels.indexOfFirst { it.id == mutableState.value.currentChannelId }.coerceAtLeast(0)
        mutableState.value = mutableState.value.copy(overlay = Overlay.CHANNEL_RAIL, railIndex = current)
        scheduleRailClose()
    }

    private fun toggleRailFavorites() {
        val snapshot = mutableState.value
        val favoritesOnly = !snapshot.favoritesOnly
        if (favoritesOnly && snapshot.favoriteChannelIds.isEmpty()) return
        val visibleChannels = if (favoritesOnly) {
            snapshot.channels.filter { it.id in snapshot.favoriteChannelIds }
        } else snapshot.channels
        val selected = visibleChannels.indexOfFirst { it.id == snapshot.currentChannelId }.coerceAtLeast(0)
        mutableState.value = snapshot.copy(
            favoritesOnly = favoritesOnly,
            railIndex = selected,
        )
        scheduleRailClose()
    }

    private fun railChannels(snapshot: TvUiState): List<Channel> =
        if (snapshot.favoritesOnly) snapshot.channels.filter { it.id in snapshot.favoriteChannelIds }
        else snapshot.channels

    private fun channelStep(delta: Int, immediate: Boolean) {
        val snapshot = mutableState.value
        val channels = if (snapshot.overlay == Overlay.CHANNEL_RAIL) railChannels(snapshot) else snapshot.channels
        if (channels.isEmpty()) return
        val base = if (snapshot.overlay == Overlay.CHANNEL_RAIL) snapshot.railIndex
        else channels.indexOfFirst { it.id == snapshot.currentChannelId }.coerceAtLeast(0)
        val next = (base + delta).floorMod(channels.size)
        mutableState.value = mutableState.value.copy(overlay = Overlay.CHANNEL_RAIL, railIndex = next)
        if (immediate) tune(channels[next])
        scheduleRailClose()
    }

    private fun scheduleRailClose() {
        railJob?.cancel()
        railJob = viewModelScope.launch {
            delay(CHANNEL_RAIL_TIMEOUT_MS)
            if (mutableState.value.overlay == Overlay.CHANNEL_RAIL) {
                mutableState.value = mutableState.value.copy(overlay = Overlay.NONE)
            }
        }
    }

    private fun toggleGuide() {
        val snapshot = mutableState.value
        val favoriteChannels = snapshot.channels.filter { it.id in snapshot.favoriteChannelIds }
        val favoritesActive = snapshot.favoritesOnly && favoriteChannels.isNotEmpty()
        val channels = if (favoritesActive) favoriteChannels else snapshot.channels
        val currentIndex = channels.indexOfFirst { it.id == snapshot.currentChannelId }.coerceAtLeast(0)
        mutableState.value = snapshot.copy(
            overlay = if (snapshot.overlay == Overlay.GUIDE) Overlay.NONE else Overlay.GUIDE,
            favoritesOnly = favoritesActive,
            guideChannelIndex = currentIndex,
            guideProgramIndex = guideProgramIndexAt(channels, currentIndex, Instant.now()),
            guideAnchor = Instant.now(),
            error = null,
        )
    }

    private fun openAppSettings() {
        mutableState.value = mutableState.value.copy(
            overlay = Overlay.APP_SETTINGS,
            appSettingsIndex = 0,
            audioTrackLabel = tvPlayer.audioTrackLabel(),
            subtitleTrackLabel = tvPlayer.subtitleTrackLabel(),
            numberInput = "",
            error = null,
        )
        if (mutableState.value.profiles.isEmpty()) {
            viewModelScope.launch {
                runCatching { repository.profiles() }
                    .onSuccess { profiles -> mutableState.value = mutableState.value.copy(profiles = profiles) }
            }
        }
    }

    private fun handleAppSettingsKey(keyCode: Int): Boolean {
        val snapshot = mutableState.value
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> mutableState.value = snapshot.copy(appSettingsIndex = (snapshot.appSettingsIndex - 1).coerceAtLeast(0))
            KeyEvent.KEYCODE_DPAD_DOWN -> mutableState.value = snapshot.copy(appSettingsIndex = (snapshot.appSettingsIndex + 1).coerceAtMost(4))
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                when (snapshot.appSettingsIndex) {
                    0 -> openProfileSettings()
                    1 -> openChannelSettings(returnOverlay = Overlay.APP_SETTINGS)
                    2 -> openAudioSettings()
                    3 -> openSubtitleSettings()
                    4 -> refreshChannelPackage()
                }
            }
            else -> return false
        }
        return true
    }

    private fun openProfileSettings() {
        val snapshot = mutableState.value
        val current = snapshot.profiles.indexOfFirst { it.id == snapshot.selectedProfileId }.coerceAtLeast(0)
        mutableState.value = snapshot.copy(
            overlay = Overlay.PROFILE_SETTINGS,
            profileSettingsIndex = current,
            settingsReturnOverlay = Overlay.APP_SETTINGS,
        )
        viewModelScope.launch {
            runCatching { repository.profiles() }
                .onSuccess { profiles ->
                    val selected = profiles.indexOfFirst { it.id == mutableState.value.selectedProfileId }.coerceAtLeast(0)
                    mutableState.value = mutableState.value.copy(profiles = profiles, profileSettingsIndex = selected)
                }
                .onFailure(::showError)
        }
    }

    private fun handleProfileSettingsKey(keyCode: Int): Boolean {
        val snapshot = mutableState.value
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> mutableState.value = snapshot.copy(profileSettingsIndex = (snapshot.profileSettingsIndex - 1).coerceAtLeast(0))
            KeyEvent.KEYCODE_DPAD_DOWN -> mutableState.value = snapshot.copy(profileSettingsIndex = (snapshot.profileSettingsIndex + 1).coerceAtMost(snapshot.profiles.lastIndex.coerceAtLeast(0)))
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                snapshot.profiles.getOrNull(snapshot.profileSettingsIndex)?.let(::switchProfile)
            }
            else -> return false
        }
        return true
    }

    private fun switchProfile(profile: Profile) {
        if (profile.id == mutableState.value.selectedProfileId) {
            mutableState.value = mutableState.value.copy(overlay = Overlay.NONE)
            return
        }
        tuneJob?.cancel()
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                selectedProfileId = profile.id,
                currentChannelId = null,
                overlay = Overlay.NONE,
                loading = true,
                error = null,
            )
            repository.closePlayback(activeTicket?.playbackSessionId)
            activeTicket = null
            repository.saveSelectedProfile(profile.id)
            runCatching { repository.refresh(profile.id) }
                .onFailure(::showError)
            mutableState.value = mutableState.value.copy(loading = false)
        }
    }

    private fun openAudioSettings() {
        val snapshot = mutableState.value
        val selected = AUDIO_LANGUAGE_OPTIONS.indexOfFirst { it.first == snapshot.audioLanguagePreference }.coerceAtLeast(0)
        mutableState.value = snapshot.copy(
            overlay = Overlay.AUDIO_SETTINGS,
            audioSettingsIndex = selected,
            settingsReturnOverlay = Overlay.APP_SETTINGS,
        )
    }

    private fun handleAudioSettingsKey(keyCode: Int): Boolean {
        val snapshot = mutableState.value
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> mutableState.value = snapshot.copy(audioSettingsIndex = (snapshot.audioSettingsIndex - 1).coerceAtLeast(0))
            KeyEvent.KEYCODE_DPAD_DOWN -> mutableState.value = snapshot.copy(audioSettingsIndex = (snapshot.audioSettingsIndex + 1).coerceAtMost(AUDIO_LANGUAGE_OPTIONS.lastIndex))
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val (language, label) = AUDIO_LANGUAGE_OPTIONS[snapshot.audioSettingsIndex]
                tvPlayer.setAudioLanguagePreference(language)
                mutableState.value = snapshot.copy(
                    overlay = Overlay.APP_SETTINGS,
                    audioLanguagePreference = language,
                    audioTrackLabel = label,
                )
                viewModelScope.launch { repository.savePreferredAudio(language) }
            }
            else -> return false
        }
        return true
    }

    private fun openSubtitleSettings() {
        val snapshot = mutableState.value
        val selected = SUBTITLE_LANGUAGE_OPTIONS.indexOfFirst { it.first == snapshot.subtitleLanguagePreference }.coerceAtLeast(0)
        mutableState.value = snapshot.copy(
            overlay = Overlay.SUBTITLE_SETTINGS,
            subtitleSettingsIndex = selected,
            settingsReturnOverlay = Overlay.APP_SETTINGS,
        )
    }

    private fun handleSubtitleSettingsKey(keyCode: Int): Boolean {
        val snapshot = mutableState.value
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> mutableState.value = snapshot.copy(subtitleSettingsIndex = (snapshot.subtitleSettingsIndex - 1).coerceAtLeast(0))
            KeyEvent.KEYCODE_DPAD_DOWN -> mutableState.value = snapshot.copy(subtitleSettingsIndex = (snapshot.subtitleSettingsIndex + 1).coerceAtMost(SUBTITLE_LANGUAGE_OPTIONS.lastIndex))
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val (language, label) = SUBTITLE_LANGUAGE_OPTIONS[snapshot.subtitleSettingsIndex]
                tvPlayer.setSubtitleLanguagePreference(language)
                mutableState.value = snapshot.copy(
                    overlay = Overlay.APP_SETTINGS,
                    subtitleLanguagePreference = language,
                    subtitleTrackLabel = label,
                )
                viewModelScope.launch { repository.savePreferredSubtitle(language) }
            }
            else -> return false
        }
        return true
    }

    private fun refreshChannelPackage() {
        val profileId = mutableState.value.selectedProfileId ?: return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(overlay = Overlay.NONE, loading = true, error = null)
            repository.clearHiddenChannels(profileId)
            runCatching { repository.refresh(profileId) }
                .onFailure(::showError)
            mutableState.value = mutableState.value.copy(loading = false)
        }
    }

    private fun openChannelSettings(channelIndex: Int? = null, returnOverlay: Overlay = Overlay.NONE) {
        val currentIndex = channelIndex
            ?: mutableState.value.channels.indexOfFirst { it.id == mutableState.value.currentChannelId }.coerceAtLeast(0)
        mutableState.value = mutableState.value.copy(
            overlay = Overlay.CHANNEL_SETTINGS,
            settingsIndex = currentIndex,
            settingsReturnOverlay = returnOverlay,
            numberInput = "",
            error = null,
        )
    }

    private fun handleChannelSettingsKey(keyCode: Int): Boolean {
        val snapshot = mutableState.value
        val channel = snapshot.channels.getOrNull(snapshot.settingsIndex) ?: return false
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> mutableState.value = snapshot.copy(settingsIndex = (snapshot.settingsIndex - 1).coerceAtLeast(0), numberInput = "")
            KeyEvent.KEYCODE_DPAD_DOWN -> mutableState.value = snapshot.copy(settingsIndex = (snapshot.settingsIndex + 1).coerceAtMost(snapshot.channels.lastIndex), numberInput = "")
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val number = (channel.serverNumber ?: snapshot.settingsIndex + 1) - 1
                assignChannelNumber(channel, number)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val number = (channel.serverNumber ?: snapshot.settingsIndex + 1) + 1
                assignChannelNumber(channel, number)
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val favorite = channel.id !in snapshot.favoriteChannelIds
                saveChannelSetting(channel, channel.serverNumber ?: snapshot.settingsIndex + 1, favorite)
            }
            else -> return false
        }
        return true
    }

    private fun saveChannelSetting(channel: Channel, number: Int, favorite: Boolean) {
        viewModelScope.launch {
            runCatching { repository.saveChannelPreference(ChannelPreference(channel.id, number, favorite)) }
                .onSuccess {
                    val snapshot = mutableState.value
                    val updatedChannels = snapshot.channels.map { if (it.id == channel.id) it.copy(serverNumber = number) else it }
                    val favorites = snapshot.favoriteChannelIds.toMutableSet().apply {
                        if (favorite) add(channel.id) else remove(channel.id)
                    }
                    mutableState.value = snapshot.copy(
                        channels = updatedChannels,
                        favoriteChannelIds = favorites,
                        favoritesOnly = snapshot.favoritesOnly && favorites.isNotEmpty(),
                        error = null,
                    )
                }
                .onFailure(::showError)
        }
    }

    private fun assignChannelNumber(
        channel: Channel,
        targetNumber: Int,
        existing: Map<String, Int> = mutableState.value.channels.associate { it.id to (it.serverNumber ?: mutableState.value.channels.indexOf(it) + 1) },
    ) {
        val snapshot = mutableState.value
        val assignments = ChannelNumberResolver.assignWithShift(existing, channel.id, targetNumber)
        if (assignments == null) {
            mutableState.value = snapshot.copy(error = "Kanalinumber peab olema vahemikus 1–999")
            return
        }
        val preferences = snapshot.channels.map { item ->
            ChannelPreference(
                channelId = item.id,
                number = assignments[item.id] ?: item.serverNumber ?: snapshot.channels.indexOf(item) + 1,
                favorite = item.id in snapshot.favoriteChannelIds,
            )
        }
        val updatedChannels = snapshot.channels
            .map { item -> item.copy(serverNumber = assignments[item.id] ?: item.serverNumber) }
            .sortedBy { it.serverNumber }
        val selectedIndex = updatedChannels.indexOfFirst { it.id == channel.id }.coerceAtLeast(0)
        mutableState.value = snapshot.copy(
            channels = updatedChannels,
            settingsIndex = selectedIndex,
            error = null,
        )
        viewModelScope.launch {
            runCatching { repository.saveChannelPreferences(preferences) }
                .onFailure(::showError)
        }
    }

    private fun guideProgramIndexAt(channels: List<Channel>, channelIndex: Int, anchor: Instant): Int {
        val channelId = channels.getOrNull(channelIndex)?.id ?: return 0
        val programs = mutableState.value.programsByChannel[channelId].orEmpty()
        val current = programs.indexOfFirst { !anchor.isBefore(it.startsAt) && anchor.isBefore(it.endsAt) }
        if (current >= 0) return current
        val next = programs.indexOfFirst { !it.startsAt.isBefore(anchor) }
        return if (next >= 0) next else programs.lastIndex.coerceAtLeast(0)
    }

    private fun tune(
        channel: Channel,
        wakeRecovery: Boolean = false,
        resetPlaybackRetry: Boolean = true,
    ) {
        val profileId = mutableState.value.selectedProfileId ?: return
        playbackRetryJob?.cancel()
        playbackRetryJob = null
        tuneJob?.cancel()
        tuneJob = viewModelScope.launch {
            if (resetPlaybackRetry) retryCount = 0
            val keepPreviousVideo = mutableState.value.videoVisible && !wakeRecovery
            mutableState.value = mutableState.value.copy(
                loading = true,
                videoVisible = keepPreviousVideo,
                error = null,
            )
            if (!keepPreviousVideo) tvPlayer.stopAndClear()
            try {
                manuallyTimeShifted = false
                val previousSessionId = activeTicket?.playbackSessionId
                activeTicket = null
                repository.closePlayback(previousSessionId)
                val ticket = requestLiveTicket(profileId, channel.id, wakeRecovery)
                activeTicket = ticket
                scheduleProlong(ticket)
                pendingChannelId = channel.id
                tvPlayer.play(
                    ticket = ticket,
                    channelName = channel.name,
                    programTitle = nowProgram(channel.id)?.title,
                )
                mutableState.value = mutableState.value.copy(currentChannelId = channel.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error is Go3Failure.NotEntitled) {
                    hideUnavailableChannel(profileId, channel)
                } else {
                    showError(error)
                }
            } finally {
                mutableState.value = mutableState.value.copy(loading = false)
            }
        }
    }

    private suspend fun requestLiveTicket(
        profileId: String,
        channelId: String,
        wakeRecovery: Boolean,
    ): PlaybackTicket {
        val retryDelays = if (wakeRecovery) listOf(350L, 900L, 1_800L, 3_000L) else emptyList()
        var lastUnavailable: Go3Failure.Unavailable? = null
        repeat(retryDelays.size + 1) { attempt ->
            try {
                return repository.liveTicket(profileId, channelId)
            } catch (error: Go3Failure.Unavailable) {
                lastUnavailable = error
                retryDelays.getOrNull(attempt)?.let { delay(it) }
            }
        }
        throw lastUnavailable ?: Go3Failure.Unavailable("Go3 ühendus ei taastunud")
    }

    private suspend fun hideUnavailableChannel(profileId: String, channel: Channel) {
        repository.hideChannel(profileId, channel.id)
        val snapshot = mutableState.value
        val removedIndex = snapshot.channels.indexOfFirst { it.id == channel.id }.coerceAtLeast(0)
        val remainingChannels = snapshot.channels.filterNot { it.id == channel.id }
        mutableState.value = snapshot.copy(
            channels = remainingChannels,
            programsByChannel = snapshot.programsByChannel - channel.id,
            favoriteChannelIds = snapshot.favoriteChannelIds - channel.id,
            favoritesOnly = snapshot.favoritesOnly && (snapshot.favoriteChannelIds - channel.id).isNotEmpty(),
            currentChannelId = snapshot.currentChannelId.takeUnless { it == channel.id },
            overlay = Overlay.NONE,
            error = null,
        )
        val fallback = remainingChannels.getOrNull(removedIndex.coerceAtMost(remainingChannels.lastIndex))
            ?: remainingChannels.firstOrNull()
        if (fallback != null) viewModelScope.launch { tune(fallback) }
    }

    private fun playCatchup(program: Program) {
        if (!program.catchupAvailable) {
            mutableState.value = mutableState.value.copy(error = "Selle saate järelvaatamine pole saadaval")
            return
        }
        val profileId = mutableState.value.selectedProfileId ?: return
        tuneJob?.cancel()
        tuneJob = viewModelScope.launch {
            val keepPreviousVideo = mutableState.value.videoVisible
            mutableState.value = mutableState.value.copy(
                loading = true,
                videoVisible = keepPreviousVideo,
                error = null,
                overlay = Overlay.NONE,
            )
            if (!keepPreviousVideo) tvPlayer.stopAndClear()
            try {
                manuallyTimeShifted = true
                val previousSessionId = activeTicket?.playbackSessionId
                activeTicket = null
                repository.closePlayback(previousSessionId)
                repository.catchupTicket(profileId, program.id).also {
                    activeTicket = it
                    scheduleProlong(it)
                    pendingChannelId = program.channelId
                    retryCount = 0
                    val channelName = mutableState.value.channels
                        .firstOrNull { channel -> channel.id == program.channelId }
                        ?.name ?: "Go3 TV+"
                    tvPlayer.play(it, channelName, program.title)
                }
            } catch (error: Exception) {
                showError(error)
            } finally {
                mutableState.value = mutableState.value.copy(loading = false)
            }
        }
    }

    override fun onReady() {
        pendingChannelId?.let { id ->
            viewModelScope.launch { repository.saveLastChannel(id) }
            pendingChannelId = null
        }
        mutableState.value = mutableState.value.copy(
            loading = false,
            error = null,
            audioTrackLabel = tvPlayer.audioTrackLabel(),
            subtitleTrackLabel = tvPlayer.subtitleTrackLabel(),
        )
        schedulePlaybackHealthCheck()
    }

    override fun onFirstFrame() {
        retryCount = 0
        mutableState.value = mutableState.value.copy(
            videoVisible = true,
            loading = false,
            error = null,
        )
    }

    override fun onError(error: PlaybackException) {
        val ticket = activeTicket ?: return showError(error)
        if (retryCount >= 3) return showError(error)
        val delaySeconds = 1 shl retryCount
        retryCount += 1
        playbackRetryJob?.cancel()
        playbackRetryJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                error = null,
                loading = true,
                videoVisible = false,
            )
            delay(delaySeconds * 1_000L)
            if (ticket.isLive) {
                val channel = mutableState.value.channels.firstOrNull {
                    it.id == mutableState.value.currentChannelId
                }
                playbackRetryJob = null
                if (channel != null) tune(channel, wakeRecovery = true, resetPlaybackRetry = false)
                else showError(error)
            } else {
                tvPlayer.play(ticket)
            }
        }
    }

    fun retry() {
        val channel = mutableState.value.channels.firstOrNull {
            it.id == mutableState.value.currentChannelId
        }
        if (channel != null) {
            mutableState.value = mutableState.value.copy(error = null, errorActionIndex = 0)
            tune(channel, wakeRecovery = true)
            return
        }
        val profileId = mutableState.value.selectedProfileId ?: return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(error = null, errorActionIndex = 0, loading = true)
            runCatching { repository.refresh(profileId) }
                .onFailure(::showError)
            mutableState.value = mutableState.value.copy(loading = false)
        }
    }

    fun clearError() { mutableState.value = mutableState.value.copy(error = null, errorActionIndex = 0) }

    fun onAppBackgrounded() {
        if (wasBackgrounded) return
        wasBackgrounded = true
        mutableState.value = mutableState.value.copy(overlay = Overlay.NONE, numberInput = "")
        tuneJob?.cancel()
        prolongJob?.cancel()
        seekUiJob?.cancel()
        seekCloseJob?.cancel()
        playbackHealthJob?.cancel()
        noticeJob?.cancel()
        playbackRetryJob?.cancel()
        playbackRetryJob = null
        tvPlayer.stopAndClear()
        val sessionId = activeTicket?.playbackSessionId
        activeTicket = null
        mutableState.value = mutableState.value.copy(videoVisible = false, loading = false, notice = null)
        cleanupScope.launch { repository.closePlayback(sessionId) }
    }

    fun onAppForegrounded() {
        if (!wasBackgrounded) return
        wasBackgrounded = false
        val channel = mutableState.value.channels.firstOrNull {
            it.id == mutableState.value.currentChannelId
        } ?: return
        tune(channel, wakeRecovery = true)
    }

    private fun scheduleProlong(ticket: PlaybackTicket) {
        prolongJob?.cancel()
        val sessionId = ticket.playbackSessionId ?: return
        val intervalSeconds = ticket.prolongIntervalSeconds?.coerceAtLeast(30) ?: return
        prolongJob = viewModelScope.launch {
            while (true) {
                delay(intervalSeconds * 1_000)
                runCatching { repository.prolongPlayback(sessionId) }
                    .onFailure { showError(it) }
            }
        }
    }

    private fun schedulePlaybackHealthCheck() {
        playbackHealthJob?.cancel()
        playbackRetryJob?.cancel()
        playbackHealthJob = viewModelScope.launch {
            while (true) {
                delay(15_000L)
                if (activeTicket?.isLive == true) {
                    val channel = mutableState.value.channels.firstOrNull {
                        it.id == mutableState.value.currentChannelId
                    }
                    if (channel != null) {
                        tvPlayer.updateNowPlaying(channel.name, nowProgram(channel.id)?.title)
                    }
                }
                if (!manuallyTimeShifted) tvPlayer.correctLiveDriftIfNeeded()
            }
        }
    }

    private fun nowProgram(channelId: String, at: Instant = Instant.now()): Program? =
        mutableState.value.programsByChannel[channelId].orEmpty().firstOrNull {
            !at.isBefore(it.startsAt) && at.isBefore(it.endsAt)
        }

    private fun showError(error: Throwable) {
        mutableState.value = mutableState.value.copy(
            error = error.message ?: "Tundmatu viga",
            errorActionIndex = 0,
            loading = false,
        )
    }

    override fun onCleared() {
        guideOkJob?.cancel()
        prolongJob?.cancel()
        seekUiJob?.cancel()
        seekCloseJob?.cancel()
        playbackHealthJob?.cancel()
        programActionJob?.cancel()
        noticeJob?.cancel()
        val sessionId = activeTicket?.playbackSessionId
        cleanupScope.launch { repository.closePlayback(sessionId) }
        tvPlayer.release()
        super.onCleared()
    }

    class Factory(private val container: AppContainer, private val player: TvPlayer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TvViewModel(container.auth, container.repository, player, container.isDemo) as T
    }
}

private const val CHANNEL_RAIL_TIMEOUT_MS = 5_000L
private const val SEEK_OVERLAY_TIMEOUT_MS = 10_000L
private const val PROGRAM_REMINDER_LEAD_MS = 60_000L
private const val PROGRAM_ACTION_GRACE_MS = 5 * 60_000L
private const val PROGRAM_ACTION_POLL_MS = 15_000L
private const val PROGRAM_NOTICE_TIMEOUT_MS = 12_000L
private val PROGRAM_COLOR_KEYS = setOf(
    KeyEvent.KEYCODE_PROG_RED,
    KeyEvent.KEYCODE_PROG_GREEN,
    KeyEvent.KEYCODE_PROG_YELLOW,
    KeyEvent.KEYCODE_PROG_BLUE,
)

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

private fun Int.isConfirmKey(): Boolean =
    this == KeyEvent.KEYCODE_DPAD_CENTER || this == KeyEvent.KEYCODE_ENTER || this == KeyEvent.KEYCODE_NUMPAD_ENTER

val AUDIO_LANGUAGE_OPTIONS = listOf(
    "et" to "Eesti",
    "en" to "Inglise",
    "ru" to "Vene",
    "auto" to "Automaatne",
)

val SUBTITLE_LANGUAGE_OPTIONS = listOf(
    null to "Väljas",
    "et" to "Eesti",
    "en" to "Inglise",
    "ru" to "Vene",
)

object ChannelNumberResolver {
    fun resolve(channels: List<Channel>, number: Int?): Channel? {
        if (number == null || number !in 1..999) return null
        return channels.firstOrNull { channel ->
            (channel.serverNumber ?: channels.indexOf(channel) + 1) == number
        }
    }

    fun isValidAssignment(existing: Map<String, Int>, channelId: String, number: Int): Boolean =
        number in 1..999 && existing.none { (id, assigned) -> id != channelId && assigned == number }

    fun assignWithShift(existing: Map<String, Int>, channelId: String, targetNumber: Int): Map<String, Int>? {
        if (targetNumber !in 1..999) return null
        val currentNumber = existing[channelId] ?: return null
        if (currentNumber == targetNumber) return existing
        if (existing.none { (id, number) -> id != channelId && number == targetNumber }) {
            return existing.toMutableMap().apply { put(channelId, targetNumber) }
        }
        return existing.mapValues { (id, number) ->
            when {
                id == channelId -> targetNumber
                targetNumber < currentNumber && number in targetNumber until currentNumber -> number + 1
                targetNumber > currentNumber && number in (currentNumber + 1)..targetNumber -> number - 1
                else -> number
            }
        }.takeIf { assignments ->
            assignments.values.all { it in 1..999 } && assignments.values.distinct().size == assignments.size
        }
    }
}
