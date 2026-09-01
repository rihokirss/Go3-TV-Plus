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
import ee.local.go3tvplus.domain.ProgramWindow
import ee.local.go3tvplus.domain.TransitBoard
import ee.local.go3tvplus.domain.TransitStopSelection
import ee.local.go3tvplus.domain.DEFAULT_MURASTE_STOP
import ee.local.go3tvplus.domain.WeatherForecast
import ee.local.go3tvplus.domain.WeatherLocation
import ee.local.go3tvplus.player.TvPlayer
import ee.local.go3tvplus.player.SeekSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

enum class Overlay {
    NONE, CHANNEL_RAIL, GUIDE, APP_SETTINGS, CHANNEL_SETTINGS, PROFILE_SETTINGS,
    AUDIO_SETTINGS, SUBTITLE_SETTINGS, DISPLAY_SETTINGS, WEATHER_LOCATION, WEATHER,
    TRANSIT_STOP_SETTINGS, TRANSIT, SEEK,
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
    val guideWindowStart: Instant? = null,
    val settingsIndex: Int = 0,
    val appSettingsIndex: Int = 0,
    val profileSettingsIndex: Int = 0,
    val audioSettingsIndex: Int = 0,
    val subtitleSettingsIndex: Int = 0,
    val displaySettingsIndex: Int = 0,
    val audioLanguagePreference: String = "et",
    val subtitleLanguagePreference: String? = null,
    val audioTrackLabel: String = "Eesti",
    val subtitleTrackLabel: String = "Väljas",
    val showClock: Boolean = false,
    val channelInfoSeconds: Int = 5,
    val seekOverlaySeconds: Int = 10,
    val seekStepSeconds: Int = 10,
    val weatherLocation: WeatherLocation? = null,
    val weatherForecast: WeatherForecast? = null,
    val weatherLoading: Boolean = false,
    val weatherError: String? = null,
    val weatherSearchQuery: String = "",
    val weatherSearchResults: List<WeatherLocation> = emptyList(),
    val weatherSearchIndex: Int = -1,
    val transitBoard: TransitBoard? = null,
    val transitStop: TransitStopSelection = DEFAULT_MURASTE_STOP,
    val transitLoading: Boolean = false,
    val transitError: String? = null,
    val transitDirectionIndex: Int = 0,
    val transitDepartureIndex: Int = 0,
    val transitStopSearchQuery: String = "",
    val transitStopSearchResults: List<TransitStopSelection> = emptyList(),
    val transitStopSearchIndex: Int = -1,
    val transitStopLoading: Boolean = false,
    val transitStopError: String? = null,
    val favoriteChannelIds: Set<String> = emptySet(),
    val numberInput: String = "",
    /** Set while a catchup stream plays, so overlays show the right programme. */
    val catchupProgram: Program? = null,
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
    private val mutableState = MutableStateFlow(
        TvUiState(
            auth = authCoordinator.state.value,
            isDemo = isDemo,
        ),
    )
    val state: StateFlow<TvUiState> = mutableState.asStateFlow()
    private var digitJob: Job? = null
    private var heldDigitKey: Int? = null
    private var guideOkJob: Job? = null
    private var guideLongPressHandled = false
    private var railJob: Job? = null
    private var channelTuneJob: Job? = null
    private var tuneJob: Job? = null
    private var prolongJob: Job? = null
    private var seekUiJob: Job? = null
    private var seekCloseJob: Job? = null
    private var playbackHealthJob: Job? = null
    private var playbackRetryJob: Job? = null
    private var programActionJob: Job? = null
    private var noticeJob: Job? = null
    private var startupRecoveryJob: Job? = null
    private var transitRefreshJob: Job? = null
    private var activeTicket: PlaybackTicket? = null
    private var pendingChannelId: String? = null
    private var pendingSeekOverlayChannelId: String? = null
    private var retryCount = 0
    private var wasBackgrounded = false
    private var manuallyTimeShifted = false
    private var previousChannelId: String? = null
    private var tuneGeneration = 0L
    private var scheduledProgramActions: Map<String, ScheduledProgramAction> = emptyMap()
    private val shownReminderIds = mutableSetOf<String>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tuneMutex = Mutex()

    init {
        tvPlayer.setListener(this)
        viewModelScope.launch {
            val playbackPreferences = repository.playbackPreferences()
            val showClock = repository.showClock()
            val channelInfoSeconds = DisplaySettingOptions.validChannelInfoSeconds(repository.channelInfoSeconds())
            val seekOverlaySeconds = DisplaySettingOptions.validSeekOverlaySeconds(repository.seekOverlaySeconds())
            val seekStepSeconds = DisplaySettingOptions.validSeekStepSeconds(repository.seekStepSeconds())
            tvPlayer.applyTrackPreferences(
                playbackPreferences.audioLanguage,
                playbackPreferences.subtitleLanguage,
            )
            mutableState.value = mutableState.value.copy(
                audioLanguagePreference = playbackPreferences.audioLanguage,
                subtitleLanguagePreference = playbackPreferences.subtitleLanguage,
                audioTrackLabel = tvPlayer.audioTrackLabel(),
                subtitleTrackLabel = tvPlayer.subtitleTrackLabel(),
                showClock = showClock,
                channelInfoSeconds = channelInfoSeconds,
                seekOverlaySeconds = seekOverlaySeconds,
                seekStepSeconds = seekStepSeconds,
            )
        }
        viewModelScope.launch {
            repository.weatherLocation()?.let { location ->
                mutableState.value = mutableState.value.copy(weatherLocation = location)
                refreshWeather(location)
            }
        }
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(transitStop = repository.transitStop())
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
                if (auth == DeviceAuthState.Approved) {
                    startStartupRecovery()
                } else {
                    startupRecoveryJob?.cancel()
                    startupRecoveryJob = null
                }
            }
        }
        viewModelScope.launch {
            // Channels alone drive the first tune, so playback never waits for
            // the (much heavier) programme indexing below.
            repository.channels.conflate().collect { rawChannels ->
                val profileId = mutableState.value.selectedProfileId
                val hiddenChannelIds = profileId?.let { repository.hiddenChannelIds(it) }.orEmpty()
                val availableRawChannels = rawChannels.filterNot { it.id in hiddenChannelIds }
                val saved = repository.channelPreferences(availableRawChannels)
                val channels = availableRawChannels.mapIndexed { index, channel ->
                    channel.copy(serverNumber = saved[channel.id]?.number ?: channel.serverNumber ?: index + 1)
                }.sortedBy { it.serverNumber }
                val availableIds = channels.mapTo(mutableSetOf(), Channel::id)
                val favoriteIds = saved.values.filter(ChannelPreference::favorite)
                    .map(ChannelPreference::channelId).filterTo(mutableSetOf()) { it in availableIds }
                mutableState.value = mutableState.value.copy(
                    channels = channels,
                    favoriteChannelIds = favoriteIds,
                    favoritesOnly = mutableState.value.favoritesOnly && favoriteIds.isNotEmpty(),
                )
                tuneInitialChannelIfNeeded()
            }
        }
        viewModelScope.launch {
            repository.guide.conflate().collect { (rawChannels, programs) ->
                val previous = mutableState.value
                val profileId = previous.selectedProfileId
                val hiddenChannelIds = profileId?.let { repository.hiddenChannelIds(it) }.orEmpty()
                val availableIds = rawChannels.filterNot { it.id in hiddenChannelIds }
                    .mapTo(mutableSetOf(), Channel::id)
                val indexedPrograms = withContext(Dispatchers.Default) {
                    programs.groupBy(Program::channelId)
                        .filterKeys { it in availableIds }
                        .mapValues { (_, channelPrograms) -> channelPrograms.sortedBy(Program::startsAt) }
                }
                var updated = previous.copy(programsByChannel = indexedPrograms)
                if (previous.overlay == Overlay.GUIDE) {
                    val channels = guideChannels(updated)
                    val channelId = channels.getOrNull(previous.guideChannelIndex)?.id
                    val previouslySelected = channelId?.let { previous.programsByChannel[it] }
                        ?.getOrNull(previous.guideProgramIndex)
                    val updatedChannelPrograms = channelId?.let(indexedPrograms::get).orEmpty()
                    val preservedIndex = previouslySelected?.let { selected ->
                        updatedChannelPrograms.indexOfFirst { candidate ->
                            candidate.id == selected.id || candidate.sameScheduleSlot(selected)
                        }
                    } ?: -1
                    updated = updated.copy(
                        guideProgramIndex = if (preservedIndex >= 0) preservedIndex else {
                            guideProgramIndexAt(
                                channels,
                                previous.guideChannelIndex,
                                previous.guideAnchor ?: Instant.now(),
                                indexedPrograms,
                            )
                        },
                    )
                }
                mutableState.value = updated
            }
        }
    }

    private suspend fun tuneInitialChannelIfNeeded() {
        val snapshot = mutableState.value
        if (snapshot.selectedProfileId == null || snapshot.currentChannelId != null) return
        if (snapshot.channels.isEmpty() || tuneJob?.isActive == true) return
        val preferred = repository.lastChannelId()
        val channel = snapshot.channels.firstOrNull { it.id == preferred }
            ?: snapshot.channels.firstOrNull { it.id in snapshot.favoriteChannelIds }
            ?: snapshot.channels.first()
        tune(channel)
    }

    fun startPairing() = authCoordinator.start()

    private fun startStartupRecovery() {
        if (startupRecoveryJob?.isActive == true) return
        startupRecoveryJob = viewModelScope.launch {
            try {
                restoreProfileOrLoadProfiles()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

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
        val remembered = withTimeoutOrNull(5_000L) { repository.selectedProfileId() }
        if (remembered == null) {
            loadProfiles()
            return
        }
        mutableState.value = mutableState.value.copy(selectedProfileId = remembered)
        tuneInitialChannelIfNeeded()
        val hasCachedChannels = repository.channels.first().isNotEmpty()
        if (hasCachedChannels) {
            // The Room flow and profile restoration start concurrently. Wait
            // briefly for the cached channel list to reach UI state, then tune
            // explicitly instead of depending on collector timing.
            withTimeoutOrNull(STARTUP_REFRESH_DEFER_MS) { state.first { it.channels.isNotEmpty() } }
            tuneInitialChannelIfNeeded()
            // Cached channels start playing right away; hold the channel/EPG
            // download back so it doesn't compete with the first video segments.
            withTimeoutOrNull(STARTUP_REFRESH_DEFER_MS) { state.first { it.videoVisible } }
        } else {
            mutableState.value = mutableState.value.copy(loading = true)
        }
        try {
            repository.refresh(remembered)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (mutableState.value.channels.isEmpty()) showError(error)
        } finally {
            if (!hasCachedChannels) mutableState.value = mutableState.value.copy(loading = false)
        }
    }

    private suspend fun loadProfiles() {
        mutableState.value = mutableState.value.copy(loading = true)
        try {
            val profiles = withTimeoutOrNull(20_000L) { repository.profiles() }
                ?: error("Profiilide laadimine aegus")
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
        } catch (cancelled: CancellationException) {
            throw cancelled
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
        if (snapshot.auth != DeviceAuthState.Approved && event.keyCode.isConfirmKey()) {
            if (
                event.action == KeyEvent.ACTION_DOWN &&
                event.repeatCount == 0 &&
                (snapshot.auth == DeviceAuthState.Idle ||
                    snapshot.auth == DeviceAuthState.Expired ||
                    snapshot.auth is DeviceAuthState.Failed)
            ) {
                startPairing()
            }
            return true
        }
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
        if (snapshot.overlay == Overlay.WEATHER && event.keyCode == KeyEvent.KEYCODE_PROG_YELLOW) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) closeWeather()
            return true
        }
        if (snapshot.overlay == Overlay.TRANSIT && event.keyCode == KeyEvent.KEYCODE_PROG_GREEN) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) closeTransit()
            return true
        }
        if (
            event.keyCode == KeyEvent.KEYCODE_PROG_GREEN &&
            snapshot.overlay == Overlay.NONE && snapshot.numberInput.isEmpty() && snapshot.currentChannelId != null
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) openTransit()
            return true
        }
        if (
            event.keyCode == KeyEvent.KEYCODE_PROG_YELLOW &&
            snapshot.overlay == Overlay.NONE && snapshot.numberInput.isEmpty() && snapshot.currentChannelId != null
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) openWeather()
            return true
        }
        if (
            event.keyCode == KeyEvent.KEYCODE_PROG_BLUE &&
            snapshot.overlay == Overlay.NONE &&
            snapshot.numberInput.isEmpty() &&
            snapshot.currentChannelId != null
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) toggleClock()
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
            if (RemoteShortcutResolver.usesPreviousChannel(digit, snapshot.numberInput, snapshot.overlay)) {
                if (event.repeatCount == 0) tunePreviousChannel()
                return true
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
                if (snapshot.overlay == Overlay.TRANSIT) transitRefreshJob?.cancel()
                val returnOverlay = if (
                    snapshot.overlay == Overlay.CHANNEL_SETTINGS || snapshot.overlay == Overlay.PROFILE_SETTINGS ||
                    snapshot.overlay == Overlay.AUDIO_SETTINGS || snapshot.overlay == Overlay.SUBTITLE_SETTINGS ||
                    snapshot.overlay == Overlay.DISPLAY_SETTINGS || snapshot.overlay == Overlay.WEATHER_LOCATION ||
                    snapshot.overlay == Overlay.WEATHER || snapshot.overlay == Overlay.TRANSIT_STOP_SETTINGS ||
                    snapshot.overlay == Overlay.TRANSIT
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
            Overlay.DISPLAY_SETTINGS -> handleDisplaySettingsKey(event.keyCode)
            Overlay.WEATHER_LOCATION -> handleWeatherLocationKey(event.keyCode)
            Overlay.WEATHER -> true
            Overlay.TRANSIT_STOP_SETTINGS -> handleTransitStopSettingsKey(event.keyCode)
            Overlay.TRANSIT -> handleTransitKey(event.keyCode)
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
        KeyEvent.KEYCODE_DPAD_UP -> {
            startViewedProgramFromBeginning()
            true
        }
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
            val next = tvPlayer.seekBy(-mutableState.value.seekStepSeconds * 1_000L)
            if (next.isLive) manuallyTimeShifted = true
            updateSeekState(next, Overlay.SEEK)
            scheduleSeekClose()
            true
        }
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
            val before = tvPlayer.seekSnapshot()
            val next = tvPlayer.seekBy(mutableState.value.seekStepSeconds * 1_000L)
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

    private fun startViewedProgramFromBeginning() {
        val snapshot = mutableState.value
        if (!snapshot.seekIsLive || snapshot.catchupProgram != null) {
            showNotice("Algusest alustamine on saadaval otseülekande ajal")
            return
        }
        val channelId = snapshot.currentChannelId ?: return
        val playbackInstant = Instant.now().minusMillis(snapshot.seekLiveOffsetMs?.coerceAtLeast(0L) ?: 0L)
        val program = nowProgram(channelId, playbackInstant)
        if (program == null) {
            showNotice("Seda saadet ei saa algusest alustada")
            return
        }
        val liveRewindMs = StartOverResolver.liveRewindMs(
            seekPositionMs = snapshot.seekPositionMs,
            programStartsAt = program.startsAt,
            playbackInstant = playbackInstant,
        )
        if (liveRewindMs != null) {
            manuallyTimeShifted = true
            updateSeekState(tvPlayer.seekBy(liveRewindMs), Overlay.SEEK)
            scheduleSeekClose()
            return
        }
        if (!program.catchupAvailable) {
            showNotice("Saate algus pole enam ajapuhvris")
            return
        }
        playCatchup(program)
    }

    private fun toggleClock(showConfirmation: Boolean = true) {
        val show = !mutableState.value.showClock
        mutableState.value = mutableState.value.copy(showClock = show)
        if (showConfirmation) showNotice(if (show) "Kell sees" else "Kell väljas", CLOCK_NOTICE_TIMEOUT_MS)
        viewModelScope.launch { repository.saveShowClock(show) }
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
            delay(mutableState.value.seekOverlaySeconds * 1_000L)
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
                val nextProgram = channelPrograms.getOrNull(next)
                mutableState.value = snapshot.copy(
                    guideProgramIndex = next,
                    guideAnchor = nextProgram?.startsAt ?: snapshot.guideAnchor,
                    guideWindowStart = nextProgram?.let {
                        ProgramWindow.guideWindowStartKeepingVisible(
                            snapshot.guideWindowStart
                                ?: ProgramWindow.guideWindowStart(snapshot.guideAnchor ?: Instant.now(), ZoneId.systemDefault()),
                            it,
                        )
                    } ?: snapshot.guideWindowStart,
                )
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val next = (snapshot.guideProgramIndex + 1).coerceAtMost(channelPrograms.lastIndex.coerceAtLeast(0))
                val nextProgram = channelPrograms.getOrNull(next)
                mutableState.value = snapshot.copy(
                    guideProgramIndex = next,
                    guideAnchor = nextProgram?.startsAt ?: snapshot.guideAnchor,
                    guideWindowStart = nextProgram?.let {
                        ProgramWindow.guideWindowStartKeepingVisible(
                            snapshot.guideWindowStart
                                ?: ProgramWindow.guideWindowStart(snapshot.guideAnchor ?: Instant.now(), ZoneId.systemDefault()),
                            it,
                        )
                    } ?: snapshot.guideWindowStart,
                )
            }
            else -> return false
        }
        return true
    }

    private fun handleGuideColorKey(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_PROG_RED -> {
                jumpGuideDay(-1)
                return
            }
            KeyEvent.KEYCODE_PROG_GREEN -> {
                jumpGuideDay(1)
                return
            }
        }
        val snapshot = mutableState.value
        val program = programsForGuideChannel(snapshot).getOrNull(snapshot.guideProgramIndex)
        if (program == null || !program.startsAt.isAfter(Instant.now())) {
            showNotice(
                if (keyCode == KeyEvent.KEYCODE_PROG_YELLOW) "Meeldetuletuse saab lisada tulevasele saatele"
                else "Automaatlülituse saab lisada tulevasele saatele",
            )
            return
        }
        val previous = scheduledProgramActions[program.id]
        val updated = when (keyCode) {
            KeyEvent.KEYCODE_PROG_YELLOW -> ScheduledProgramAction(
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
            else -> return
        }.takeIf { it.reminder || it.autoTune }

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

    private fun showNotice(message: String, timeoutMs: Long = PROGRAM_NOTICE_TIMEOUT_MS) {
        mutableState.value = mutableState.value.copy(notice = message)
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(timeoutMs)
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

    private fun jumpGuideDay(direction: Long) {
        val snapshot = mutableState.value
        val channels = guideChannels(snapshot)
        if (channels.isEmpty()) return
        val target = (snapshot.guideAnchor ?: Instant.now()).plus(Duration.ofHours(24L * direction))
        val programIndex = guideProgramIndexAt(channels, snapshot.guideChannelIndex, target)
        val resolvedStart = snapshot.programsByChannel[channels.getOrNull(snapshot.guideChannelIndex)?.id]
            .orEmpty().getOrNull(programIndex)?.startsAt
        mutableState.value = snapshot.copy(
            guideProgramIndex = programIndex,
            guideAnchor = resolvedStart ?: target,
            guideWindowStart = ProgramWindow.guideWindowStart(resolvedStart ?: target, ZoneId.systemDefault()),
        )
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
        when (ProgramWindow.guideSelectionAction(program, Instant.now())) {
            ProgramWindow.GuideSelectionAction.PLAY_CATCHUP -> program?.let(::playCatchup)
            ProgramWindow.GuideSelectionAction.TUNE_LIVE -> if (channel != null) {
                mutableState.value = snapshot.copy(overlay = Overlay.NONE, error = null, errorActionIndex = 0)
                tune(channel)
            }
            ProgramWindow.GuideSelectionAction.SHOW_INFO -> if (program != null) {
                // A future programme is informational, not a playback failure.
                // Keep the guide and its description visible instead of showing
                // the global retry/error banner.
                mutableState.value = snapshot.copy(error = null, errorActionIndex = 0)
                showNotice(program.description?.takeIf(String::isNotBlank) ?: "${program.title} pole veel alanud")
            }
        }
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

    private fun tunePreviousChannel() {
        val snapshot = mutableState.value
        val target = snapshot.channels.firstOrNull { it.id == previousChannelId }
        if (target == null) {
            showNotice("Eelmist kanalit pole veel")
            return
        }
        railJob?.cancel()
        seekUiJob?.cancel()
        seekCloseJob?.cancel()
        pendingSeekOverlayChannelId = target.id
        mutableState.value = snapshot.copy(
            overlay = Overlay.NONE,
            numberInput = "",
        )
        tune(target)
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
        if (immediate) {
            channelTuneJob?.cancel()
            channelTuneJob = viewModelScope.launch {
                delay(CHANNEL_TUNE_DEBOUNCE_MS)
                channelTuneJob = null
                val selected = railChannels(mutableState.value).getOrNull(mutableState.value.railIndex)
                if (selected != null) tune(selected)
            }
        }
        scheduleRailClose()
    }

    private fun scheduleRailClose() {
        railJob?.cancel()
        railJob = viewModelScope.launch {
            delay(mutableState.value.channelInfoSeconds * 1_000L)
            if (mutableState.value.overlay == Overlay.CHANNEL_RAIL) {
                mutableState.value = mutableState.value.copy(overlay = Overlay.NONE)
            }
        }
    }

    private fun toggleGuide() {
        val snapshot = mutableState.value
        val now = Instant.now()
        val favoriteChannels = snapshot.channels.filter { it.id in snapshot.favoriteChannelIds }
        val favoritesActive = snapshot.favoritesOnly && favoriteChannels.isNotEmpty()
        val channels = if (favoritesActive) favoriteChannels else snapshot.channels
        val currentIndex = channels.indexOfFirst { it.id == snapshot.currentChannelId }.coerceAtLeast(0)
        mutableState.value = snapshot.copy(
            overlay = if (snapshot.overlay == Overlay.GUIDE) Overlay.NONE else Overlay.GUIDE,
            favoritesOnly = favoritesActive,
            guideChannelIndex = currentIndex,
            guideProgramIndex = guideProgramIndexAt(channels, currentIndex, now),
            guideAnchor = now,
            guideWindowStart = ProgramWindow.guideWindowStart(now, ZoneId.systemDefault()),
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
            KeyEvent.KEYCODE_DPAD_DOWN -> mutableState.value = snapshot.copy(appSettingsIndex = (snapshot.appSettingsIndex + 1).coerceAtMost(7))
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                when (snapshot.appSettingsIndex) {
                    0 -> openProfileSettings()
                    1 -> openChannelSettings(returnOverlay = Overlay.APP_SETTINGS)
                    2 -> openAudioSettings()
                    3 -> openSubtitleSettings()
                    4 -> openDisplaySettings()
                    5 -> openWeatherLocationSettings(Overlay.APP_SETTINGS)
                    6 -> openTransitStopSettings()
                    7 -> refreshChannelPackage()
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
        previousChannelId = null
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

    private fun openDisplaySettings() {
        mutableState.value = mutableState.value.copy(
            overlay = Overlay.DISPLAY_SETTINGS,
            displaySettingsIndex = 0,
            settingsReturnOverlay = Overlay.APP_SETTINGS,
        )
    }

    private fun handleDisplaySettingsKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> {
            mutableState.value = mutableState.value.copy(
                displaySettingsIndex = (mutableState.value.displaySettingsIndex - 1).coerceAtLeast(0),
            )
            true
        }
        KeyEvent.KEYCODE_DPAD_DOWN -> {
            mutableState.value = mutableState.value.copy(
                displaySettingsIndex = (mutableState.value.displaySettingsIndex + 1).coerceAtMost(3),
            )
            true
        }
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
            val snapshot = mutableState.value
            val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
            when (snapshot.displaySettingsIndex) {
                0 -> toggleClock(showConfirmation = false)
                1 -> {
                    val seconds = DisplaySettingOptions.cycleChannelInfoSeconds(snapshot.channelInfoSeconds, direction)
                    mutableState.value = snapshot.copy(channelInfoSeconds = seconds)
                    viewModelScope.launch { repository.saveChannelInfoSeconds(seconds) }
                }
                2 -> {
                    val seconds = DisplaySettingOptions.cycleSeekOverlaySeconds(snapshot.seekOverlaySeconds, direction)
                    mutableState.value = snapshot.copy(seekOverlaySeconds = seconds)
                    viewModelScope.launch { repository.saveSeekOverlaySeconds(seconds) }
                }
                3 -> {
                    val seconds = DisplaySettingOptions.cycleSeekStepSeconds(snapshot.seekStepSeconds, direction)
                    mutableState.value = snapshot.copy(seekStepSeconds = seconds)
                    viewModelScope.launch { repository.saveSeekStepSeconds(seconds) }
                }
            }
            true
        }
        else -> false
    }

    private fun openWeather() {
        val location = mutableState.value.weatherLocation
        if (location == null) {
            openWeatherLocationSettings(Overlay.NONE)
            return
        }
        mutableState.value = mutableState.value.copy(
            overlay = Overlay.WEATHER,
            settingsReturnOverlay = Overlay.NONE,
            weatherError = null,
        )
        val stale = mutableState.value.weatherForecast?.let {
            Duration.between(it.fetchedAt, Instant.now()) > Duration.ofMinutes(15)
        } != false
        if (stale) refreshWeather(location)
    }

    private fun closeWeather() {
        mutableState.value = mutableState.value.copy(
            overlay = mutableState.value.settingsReturnOverlay,
            weatherError = null,
        )
    }

    private fun openTransit() {
        transitRefreshJob?.cancel()
        mutableState.value = mutableState.value.copy(
            overlay = Overlay.TRANSIT,
            settingsReturnOverlay = Overlay.NONE,
            transitError = null,
            transitDepartureIndex = 0,
            transitDirectionIndex = mutableState.value.transitDirectionIndex
                .coerceIn(0, (mutableState.value.transitStop.platforms.lastIndex).coerceAtLeast(0)),
        )
        transitRefreshJob = viewModelScope.launch {
            while (mutableState.value.overlay == Overlay.TRANSIT) {
                refreshTransitBoard()
                delay(TRANSIT_REFRESH_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshTransitBoard() {
        mutableState.value = mutableState.value.copy(transitLoading = true, transitError = null)
        runCatching { repository.transitDepartures(mutableState.value.transitStop) }
            .onSuccess { board ->
                if (mutableState.value.overlay == Overlay.TRANSIT) {
                    mutableState.value = mutableState.value.copy(
                        transitBoard = board,
                        transitLoading = false,
                        transitError = null,
                    )
                }
            }
            .onFailure { error ->
                if (mutableState.value.overlay == Overlay.TRANSIT) {
                    mutableState.value = mutableState.value.copy(
                        transitLoading = false,
                        transitError = "Bussiaegade värskendamine ebaõnnestus: ${error.message ?: "tundmatu viga"}",
                    )
                }
            }
    }

    private fun closeTransit() {
        transitRefreshJob?.cancel()
        mutableState.value = mutableState.value.copy(
            overlay = mutableState.value.settingsReturnOverlay,
            transitLoading = false,
            transitError = null,
        )
    }

    private fun handleTransitKey(keyCode: Int): Boolean {
        val snapshot = mutableState.value
        val directionStopCode = snapshot.transitStop.platforms
            .getOrNull(snapshot.transitDirectionIndex)?.code.orEmpty()
        val departureCount = snapshot.transitBoard?.departures
            ?.count { it.stopCode == directionStopCode }
            ?: 0
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> mutableState.value = snapshot.copy(
                transitDirectionIndex = (snapshot.transitDirectionIndex - 1).coerceAtLeast(0),
                transitDepartureIndex = 0,
            )
            KeyEvent.KEYCODE_DPAD_RIGHT -> mutableState.value = snapshot.copy(
                transitDirectionIndex = (snapshot.transitDirectionIndex + 1)
                    .coerceAtMost(snapshot.transitStop.platforms.lastIndex.coerceAtLeast(0)),
                transitDepartureIndex = 0,
            )
            KeyEvent.KEYCODE_DPAD_UP -> mutableState.value = snapshot.copy(
                transitDepartureIndex = (snapshot.transitDepartureIndex - 1).coerceAtLeast(0),
            )
            KeyEvent.KEYCODE_DPAD_DOWN -> mutableState.value = snapshot.copy(
                transitDepartureIndex = (snapshot.transitDepartureIndex + 1)
                    .coerceAtMost((departureCount - 1).coerceAtLeast(0)),
            )
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> openTransit()
        }
        return true
    }

    private fun openTransitStopSettings() {
        val stop = mutableState.value.transitStop
        mutableState.value = mutableState.value.copy(
            overlay = Overlay.TRANSIT_STOP_SETTINGS,
            settingsReturnOverlay = Overlay.APP_SETTINGS,
            transitStopSearchQuery = stop.name,
            transitStopSearchResults = listOf(stop),
            transitStopSearchIndex = -1,
            transitStopError = null,
        )
    }

    fun updateTransitStopSearchQuery(query: String) {
        mutableState.value = mutableState.value.copy(
            transitStopSearchQuery = query.take(50),
            transitStopSearchResults = emptyList(),
            transitStopSearchIndex = -1,
            transitStopError = null,
        )
    }

    fun searchTransitStops() {
        val query = mutableState.value.transitStopSearchQuery.trim()
        if (query.length < 2 || mutableState.value.transitStopLoading) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(transitStopLoading = true, transitStopError = null)
            runCatching { repository.searchTransitStops(query) }
                .onSuccess { results ->
                    mutableState.value = mutableState.value.copy(
                        transitStopLoading = false,
                        transitStopSearchResults = results,
                        transitStopSearchIndex = -1,
                        transitStopError = if (results.isEmpty()) "Peatust ei leitud" else null,
                    )
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        transitStopLoading = false,
                        transitStopError = "Peatuse otsing ebaõnnestus: ${error.message ?: "tundmatu viga"}",
                    )
                }
        }
    }

    fun handleFocusedSearchKey(keyCode: Int) {
        when (mutableState.value.overlay) {
            Overlay.WEATHER_LOCATION -> handleWeatherLocationKey(keyCode)
            Overlay.TRANSIT_STOP_SETTINGS -> handleTransitStopSettingsKey(keyCode)
            else -> Unit
        }
    }

    private fun handleTransitStopSettingsKey(keyCode: Int): Boolean {
        val snapshot = mutableState.value
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> mutableState.value = snapshot.copy(
                transitStopSearchIndex = SearchSelectionResolver.move(
                    snapshot.transitStopSearchIndex,
                    snapshot.transitStopSearchResults.size,
                    -1,
                ),
            )
            KeyEvent.KEYCODE_DPAD_DOWN -> mutableState.value = snapshot.copy(
                transitStopSearchIndex = SearchSelectionResolver.move(
                    snapshot.transitStopSearchIndex,
                    snapshot.transitStopSearchResults.size,
                    1,
                ),
            )
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val selected = snapshot.transitStopSearchResults.getOrNull(snapshot.transitStopSearchIndex)
                if (selected == null) searchTransitStops() else selectTransitStop(selected)
            }
            else -> return false
        }
        return true
    }

    private fun selectTransitStop(stop: TransitStopSelection) {
        mutableState.value = mutableState.value.copy(
            overlay = mutableState.value.settingsReturnOverlay,
            transitStop = stop,
            transitBoard = null,
            transitDirectionIndex = 0,
            transitDepartureIndex = 0,
            transitStopSearchQuery = stop.name,
            transitStopSearchResults = emptyList(),
            transitStopError = null,
        )
        viewModelScope.launch { repository.saveTransitStop(stop) }
    }

    private fun openWeatherLocationSettings(returnOverlay: Overlay) {
        val location = mutableState.value.weatherLocation
        mutableState.value = mutableState.value.copy(
            overlay = Overlay.WEATHER_LOCATION,
            settingsReturnOverlay = returnOverlay,
            weatherSearchQuery = location?.name.orEmpty(),
            weatherSearchResults = location?.let(::listOf).orEmpty(),
            weatherSearchIndex = -1,
            weatherError = null,
        )
    }

    fun updateWeatherSearchQuery(query: String) {
        mutableState.value = mutableState.value.copy(
            weatherSearchQuery = query.take(50),
            weatherSearchResults = emptyList(),
            weatherSearchIndex = -1,
            weatherError = null,
        )
    }

    fun searchWeatherLocations() {
        val query = mutableState.value.weatherSearchQuery.trim()
        if (query.length < 2 || mutableState.value.weatherLoading) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(weatherLoading = true, weatherError = null)
            runCatching { repository.searchWeatherLocations(query) }
                .onSuccess { results ->
                    mutableState.value = mutableState.value.copy(
                        weatherLoading = false,
                        weatherSearchResults = results,
                        weatherSearchIndex = -1,
                        weatherError = if (results.isEmpty()) "Asulat ei leitud" else null,
                    )
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        weatherLoading = false,
                        weatherError = "Asukoha otsing ebaõnnestus: ${error.message ?: "tundmatu viga"}",
                    )
                }
        }
    }

    private fun handleWeatherLocationKey(keyCode: Int): Boolean {
        val snapshot = mutableState.value
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> mutableState.value = snapshot.copy(
                weatherSearchIndex = SearchSelectionResolver.move(
                    snapshot.weatherSearchIndex,
                    snapshot.weatherSearchResults.size,
                    -1,
                ),
            )
            KeyEvent.KEYCODE_DPAD_DOWN -> mutableState.value = snapshot.copy(
                weatherSearchIndex = SearchSelectionResolver.move(
                    snapshot.weatherSearchIndex,
                    snapshot.weatherSearchResults.size,
                    1,
                ),
            )
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val selected = snapshot.weatherSearchResults.getOrNull(snapshot.weatherSearchIndex)
                if (selected == null) searchWeatherLocations() else selectWeatherLocation(selected)
            }
            else -> return false
        }
        return true
    }

    private fun selectWeatherLocation(location: WeatherLocation) {
        val returnOverlay = mutableState.value.settingsReturnOverlay
        mutableState.value = mutableState.value.copy(
            weatherLocation = location,
            overlay = returnOverlay,
            weatherSearchQuery = location.name,
            weatherSearchResults = emptyList(),
            weatherError = null,
        )
        viewModelScope.launch { repository.saveWeatherLocation(location) }
        refreshWeather(location)
    }

    private fun refreshWeather(location: WeatherLocation) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(weatherLoading = true, weatherError = null)
            runCatching { repository.weatherForecast(location) }
                .onSuccess { forecast ->
                    if (mutableState.value.weatherLocation == location) {
                        mutableState.value = mutableState.value.copy(
                            weatherForecast = forecast,
                            weatherLoading = false,
                            weatherError = null,
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        weatherLoading = false,
                        weatherError = "Ilma värskendamine ebaõnnestus: ${error.message ?: "tundmatu viga"}",
                    )
                }
        }
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

    private fun guideProgramIndexAt(
        channels: List<Channel>,
        channelIndex: Int,
        anchor: Instant,
        programsByChannel: Map<String, List<Program>> = mutableState.value.programsByChannel,
    ): Int {
        val channelId = channels.getOrNull(channelIndex)?.id ?: return 0
        val programs = programsByChannel[channelId].orEmpty()
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
        if (pendingSeekOverlayChannelId != null && pendingSeekOverlayChannelId != channel.id) {
            pendingSeekOverlayChannelId = null
        }
        channelTuneJob?.cancel()
        channelTuneJob = null
        playbackRetryJob?.cancel()
        transitRefreshJob?.cancel()
        playbackRetryJob = null
        val generation = ++tuneGeneration
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
            var acquiredTicket: PlaybackTicket? = null
            var adoptedTicket = false
            try {
                tuneMutex.withLock {
                    ensureActive()
                    manuallyTimeShifted = false
                    prolongJob?.cancel()
                    val previousSessionId = activeTicket?.playbackSessionId
                    activeTicket = null
                    withContext(NonCancellable) { repository.closePlayback(previousSessionId) }
                    ensureActive()
                    withContext(NonCancellable) {
                        acquiredTicket = requestLiveTicket(profileId, channel.id, wakeRecovery)
                    }
                    if (generation != tuneGeneration) return@withLock
                    ensureActive()
                    val ticket = acquiredTicket ?: return@withLock
                    activeTicket = ticket
                    scheduleProlong(ticket)
                    pendingChannelId = channel.id
                    tvPlayer.play(
                        ticket = ticket,
                        channelName = channel.name,
                        programTitle = nowProgram(channel.id)?.title,
                    )
                    previousChannelId = PreviousChannelResolver.afterSuccessfulTune(
                        previousChannelId = previousChannelId,
                        currentChannelId = mutableState.value.currentChannelId,
                        tunedChannelId = channel.id,
                    )
                    mutableState.value = mutableState.value.copy(currentChannelId = channel.id, catchupProgram = null)
                    adoptedTicket = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == tuneGeneration) {
                    if (pendingSeekOverlayChannelId == channel.id) pendingSeekOverlayChannelId = null
                    if (error is Go3Failure.NotEntitled) {
                        hideUnavailableChannel(profileId, channel)
                    } else {
                        showError(error)
                    }
                }
            } finally {
                if (!adoptedTicket) {
                    val abandonedSessionId = acquiredTicket?.playbackSessionId
                    if (activeTicket === acquiredTicket) activeTicket = null
                    withContext(NonCancellable) { repository.closePlayback(abandonedSessionId) }
                }
                if (generation == tuneGeneration) {
                    mutableState.value = mutableState.value.copy(loading = false)
                }
            }
        }
    }

    private suspend fun requestLiveTicket(
        profileId: String,
        channelId: String,
        wakeRecovery: Boolean,
    ): PlaybackTicket {
        val retryDelays = if (wakeRecovery) listOf(350L, 900L, 1_800L, 3_000L) else listOf(350L, 900L, 1_800L)
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
                val (resolvedProgram, ticket) = catchupTicketWithRefresh(profileId, program)
                repository.closePlayback(previousSessionId)
                ticket.also {
                    activeTicket = it
                    scheduleProlong(it)
                    pendingChannelId = resolvedProgram.channelId
                    retryCount = 0
                    val channelName = mutableState.value.channels
                        .firstOrNull { channel -> channel.id == resolvedProgram.channelId }
                        ?.name ?: "Go3 TV+"
                    mutableState.value = mutableState.value.copy(catchupProgram = resolvedProgram)
                    tvPlayer.play(it, channelName, resolvedProgram.title)
                }
            } catch (error: Exception) {
                showError(error)
            } finally {
                mutableState.value = mutableState.value.copy(loading = false)
            }
        }
    }

    private suspend fun catchupTicketWithRefresh(
        profileId: String,
        requestedProgram: Program,
    ): Pair<Program, PlaybackTicket> {
        try {
            return requestedProgram to repository.catchupTicket(profileId, requestedProgram.id)
        } catch (error: Go3Failure.HttpStatus) {
            if (error.statusCode != 404) throw error
        }

        val refreshedPrograms = repository.refreshProgramSlot(profileId, requestedProgram)
        val refreshedProgram = ProgramWindow.deduplicateSchedule(refreshedPrograms)
            .lastOrNull { it.sameScheduleSlot(requestedProgram) }
            ?: requestedProgram
        delay(500L)
        return try {
            refreshedProgram to repository.catchupTicket(profileId, refreshedProgram.id)
        } catch (error: Go3Failure.HttpStatus) {
            if (error.statusCode != 404) throw error
            throw Go3Failure.Unavailable(
                "Saate salvestus pole veel valmis. Proovi mõne hetke pärast uuesti.",
                error,
            )
        }
    }

    private fun Program.sameScheduleSlot(other: Program): Boolean =
        channelId == other.channelId && startsAt == other.startsAt && endsAt == other.endsAt

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
        if (pendingSeekOverlayChannelId == mutableState.value.currentChannelId) {
            pendingSeekOverlayChannelId = null
            openSeekOverlay()
        }
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
        val profileId = mutableState.value.selectedProfileId
        if (profileId == null) {
            mutableState.value = mutableState.value.copy(error = null, errorActionIndex = 0, loading = true)
            startStartupRecovery()
            return
        }
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
        startupRecoveryJob?.cancel()
        startupRecoveryJob = null
        mutableState.value = mutableState.value.copy(overlay = Overlay.NONE, numberInput = "")
        tuneJob?.cancel()
        channelTuneJob?.cancel()
        channelTuneJob = null
        pendingSeekOverlayChannelId = null
        tuneGeneration++
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
        val snapshot = mutableState.value
        if (
            snapshot.auth == DeviceAuthState.Approved &&
            (snapshot.selectedProfileId == null || snapshot.channels.isEmpty() || snapshot.currentChannelId == null)
        ) {
            startStartupRecovery()
        }
        if (!wasBackgrounded) return
        wasBackgrounded = false
        if (snapshot.selectedProfileId == null || snapshot.channels.isEmpty() || snapshot.currentChannelId == null) return
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
        startupRecoveryJob?.cancel()
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

private const val STARTUP_REFRESH_DEFER_MS = 5_000L
private const val CHANNEL_TUNE_DEBOUNCE_MS = 180L
private const val PROGRAM_REMINDER_LEAD_MS = 60_000L
private const val PROGRAM_ACTION_GRACE_MS = 5 * 60_000L
private const val PROGRAM_ACTION_POLL_MS = 15_000L
private const val PROGRAM_NOTICE_TIMEOUT_MS = 5_000L
private const val CLOCK_NOTICE_TIMEOUT_MS = PROGRAM_NOTICE_TIMEOUT_MS
private const val TRANSIT_REFRESH_INTERVAL_MS = 30_000L
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

internal object RemoteShortcutResolver {
    fun usesPreviousChannel(digit: Int, pendingDigits: String, overlay: Overlay): Boolean =
        digit == 0 && pendingDigits.isEmpty() && overlay != Overlay.CHANNEL_SETTINGS
}

internal object SearchSelectionResolver {
    fun move(currentIndex: Int, resultCount: Int, direction: Int): Int {
        if (resultCount <= 0) return -1
        return (currentIndex + direction.coerceIn(-1, 1)).coerceIn(-1, resultCount - 1)
    }
}

internal object PreviousChannelResolver {
    fun afterSuccessfulTune(
        previousChannelId: String?,
        currentChannelId: String?,
        tunedChannelId: String,
    ): String? = if (currentChannelId != null && currentChannelId != tunedChannelId) {
        currentChannelId
    } else {
        previousChannelId
    }
}

internal object StartOverResolver {
    fun liveRewindMs(
        seekPositionMs: Long,
        programStartsAt: Instant,
        playbackInstant: Instant,
    ): Long? {
        val elapsedMs = Duration.between(programStartsAt, playbackInstant).toMillis()
        return if (elapsedMs in 1..seekPositionMs.coerceAtLeast(0L)) -elapsedMs else null
    }
}

internal object DisplaySettingOptions {
    private val channelInfoSeconds = listOf(3, 5, 8)
    private val seekOverlaySeconds = listOf(5, 10, 15)
    private val seekStepSeconds = listOf(10, 30, 60)

    fun validChannelInfoSeconds(value: Int): Int = value.takeIf(channelInfoSeconds::contains) ?: 5
    fun validSeekOverlaySeconds(value: Int): Int = value.takeIf(seekOverlaySeconds::contains) ?: 10
    fun validSeekStepSeconds(value: Int): Int = value.takeIf(seekStepSeconds::contains) ?: 10
    fun cycleChannelInfoSeconds(current: Int, direction: Int): Int = cycle(channelInfoSeconds, current, direction)
    fun cycleSeekOverlaySeconds(current: Int, direction: Int): Int = cycle(seekOverlaySeconds, current, direction)
    fun cycleSeekStepSeconds(current: Int, direction: Int): Int = cycle(seekStepSeconds, current, direction)

    private fun cycle(values: List<Int>, current: Int, direction: Int): Int {
        val index = values.indexOf(current).takeIf { it >= 0 } ?: 0
        return values[(index + if (direction < 0) -1 else 1).floorMod(values.size)]
    }
}
