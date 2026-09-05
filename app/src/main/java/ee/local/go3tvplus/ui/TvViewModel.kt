package ee.local.go3tvplus.ui

import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import ee.local.go3tvplus.AppContainer
import ee.local.go3tvplus.data.AuthCoordinator
import ee.local.go3tvplus.data.IlmateenistusGateway
import ee.local.go3tvplus.data.OpenMeteoWeatherGateway
import ee.local.go3tvplus.data.PeatusTransitGateway
import ee.local.go3tvplus.data.TvRepository
import ee.local.go3tvplus.data.local.ChannelPreference
import ee.local.go3tvplus.data.local.ScheduledProgramAction
import ee.local.go3tvplus.data.local.TvPreferences
import ee.local.go3tvplus.domain.Channel
import ee.local.go3tvplus.domain.SeaLocationPreferences
import ee.local.go3tvplus.domain.DeviceAuthState
import ee.local.go3tvplus.domain.Go3Failure
import ee.local.go3tvplus.domain.PlaybackTicket
import ee.local.go3tvplus.domain.Profile
import ee.local.go3tvplus.domain.Program
import ee.local.go3tvplus.domain.ProgramWindow
import ee.local.go3tvplus.domain.TransitStopSelection
import ee.local.go3tvplus.domain.WeatherLocation
import ee.local.go3tvplus.domain.number
import ee.local.go3tvplus.player.SeekSnapshot
import ee.local.go3tvplus.player.TvPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class TvViewModel(
    private val authCoordinator: AuthCoordinator,
    private val repository: TvRepository,
    private val preferences: TvPreferences,
    private val weatherGateway: OpenMeteoWeatherGateway,
    private val stationsGateway: IlmateenistusGateway,
    private val transitGateway: PeatusTransitGateway,
    private val tvPlayer: TvPlayer,
    isDemo: Boolean,
) : ViewModel(), TvPlayer.Listener {
    val mediaPlayer get() = tvPlayer.player
    private val mutableState = MutableStateFlow(TvUiState(auth = authCoordinator.state.value, isDemo = isDemo))
    val state: StateFlow<TvUiState> = mutableState.asStateFlow()
    private val snapshot get() = mutableState.value

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
    private var overlayRefreshJob: Job? = null
    private var seaForecastJob: Job? = null
    private var seaCatalogJob: Job? = null
    private var activeTicket: PlaybackTicket? = null
    private var pendingChannelId: String? = null
    private var pendingSeekOverlayChannelId: String? = null
    private var retryCount = 0
    private var wasBackgrounded = false
    private var manuallyTimeShifted = false
    private var previousChannelId: String? = null
    private var tuneGeneration = 0L
    private var lastGuideRefreshAt: Instant? = null
    private var scheduledProgramActions: Map<String, ScheduledProgramAction> = emptyMap()
    private val shownReminderIds = mutableSetOf<String>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tuneMutex = Mutex()

    init {
        tvPlayer.setListener(this)
        viewModelScope.launch {
            val playback = preferences.playbackPreferences()
            val display = preferences.displayPreferences()
            tvPlayer.applyTrackPreferences(playback.audioLanguage, playback.subtitleLanguage)
            mutableState.update {
                it.copy(
                    audioLanguagePreference = playback.audioLanguage,
                    subtitleLanguagePreference = playback.subtitleLanguage,
                    showClock = display.showClock,
                    channelInfoSeconds = DisplaySetting.CHANNEL_INFO.valid(display.channelInfoSeconds),
                    seekOverlaySeconds = DisplaySetting.SEEK_OVERLAY.valid(display.seekOverlaySeconds),
                    seekStepSeconds = DisplaySetting.SEEK_STEP.valid(display.seekStepSeconds),
                )
            }
        }
        viewModelScope.launch {
            val location = preferences.weatherLocation()
            val stop = preferences.transitStop()
            val seaLocations = preferences.seaLocations()
            mutableState.update {
                it.copy(
                    weather = it.weather.copy(location = location),
                    transit = it.transit.copy(stop = stop),
                    seaSettings = it.seaSettings.copy(preferences = seaLocations),
                )
            }
        }
        programActionJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            scheduledProgramActions = preferences.scheduledProgramActions()
                .filter { it.startsAtEpochMs >= now - PROGRAM_ACTION_GRACE_MS }
                .associateBy(ScheduledProgramAction::programId)
            publishScheduledProgramActions()
            preferences.saveScheduledProgramActions(scheduledProgramActions.values)
            runProgramActionScheduler()
        }
        viewModelScope.launch {
            while (true) {
                delay(GUIDE_REFRESH_CHECK_MS)
                refreshGuideIfStale()
            }
        }
        viewModelScope.launch {
            authCoordinator.state.collect { auth ->
                mutableState.update { it.copy(auth = auth, error = null) }
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
                val profileId = snapshot.selectedProfileId
                val hiddenChannelIds = profileId?.let { preferences.hiddenChannels(it) }.orEmpty()
                val availableRawChannels = rawChannels.filterNot { it.id in hiddenChannelIds }
                val saved = preferences.channelPreferences(availableRawChannels.map(Channel::id))
                val channels = availableRawChannels.mapIndexed { index, channel ->
                    channel.copy(serverNumber = saved[channel.id]?.number ?: channel.serverNumber ?: index + 1)
                }.sortedBy(Channel::number)
                val availableIds = channels.mapTo(mutableSetOf(), Channel::id)
                val favoriteIds = saved.values.filter(ChannelPreference::favorite)
                    .map(ChannelPreference::channelId).filterTo(mutableSetOf()) { it in availableIds }
                mutableState.update {
                    it.copy(
                        channels = channels,
                        favoriteChannelIds = favoriteIds,
                        favoritesOnly = it.favoritesOnly && favoriteIds.isNotEmpty(),
                    )
                }
                tuneInitialChannelIfNeeded()
            }
        }
        viewModelScope.launch {
            repository.programs.conflate().collect { programs ->
                // Deduplication already orders by channel and start time, so grouping keeps each channel sorted.
                val indexedPrograms = withContext(Dispatchers.Default) { programs.groupBy(Program::channelId) }
                // Programme indexing can take long enough for profile restoration,
                // channel tuning or an overlay change to complete meanwhile. Merge
                // into the latest state atomically instead of publishing the stale
                // snapshot from before the background work.
                mutableState.update { latest ->
                    val updated = latest.copy(programsByChannel = indexedPrograms)
                    if (latest.overlay != Overlay.GUIDE) return@update updated
                    val channels = latest.visibleChannels
                    val channelId = channels.getOrNull(latest.guideChannelIndex)?.id
                    val previouslySelected = channelId?.let { latest.programsByChannel[it] }?.getOrNull(latest.guideProgramIndex)
                    val preservedIndex = previouslySelected?.let { selected ->
                        indexedPrograms[channelId].orEmpty().indexOfFirst { candidate ->
                            candidate.id == selected.id || candidate.sameScheduleSlot(selected)
                        }
                    } ?: -1
                    updated.copy(
                        guideProgramIndex = if (preservedIndex >= 0) preservedIndex else {
                            guideProgramIndexAt(channels, latest.guideChannelIndex, latest.guideAnchor ?: Instant.now(), indexedPrograms)
                        },
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- startup

    private suspend fun tuneInitialChannelIfNeeded() {
        val current = snapshot
        if (current.selectedProfileId == null || current.currentChannelId != null) return
        if (current.channels.isEmpty() || tuneJob?.isActive == true) return
        val preferred = preferences.lastChannel()
        val channel = current.channels.firstOrNull { it.id == preferred }
            ?: current.channels.firstOrNull { it.id in current.favoriteChannelIds }
            ?: current.channels.first()
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
            preferences.saveSelectedProfile(profile.id)
            val firstLoad = snapshot.channels.isEmpty()
            mutableState.update { it.copy(selectedProfileId = profile.id, loading = firstLoad) }
            refreshPackage(profile.id, errorsOnlyWhenEmpty = true)
            mutableState.update { it.copy(loading = false) }
        }
    }

    private suspend fun restoreProfileOrLoadProfiles() {
        val remembered = withTimeoutOrNull(5_000L) { preferences.selectedProfile() }
        if (remembered == null) {
            loadProfiles()
            return
        }
        mutableState.update { it.copy(selectedProfileId = remembered) }
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
            mutableState.update { it.copy(loading = true) }
        }
        try {
            refreshPackage(remembered, errorsOnlyWhenEmpty = true)
        } finally {
            if (!hasCachedChannels) mutableState.update { it.copy(loading = false) }
            tuneInitialChannelIfNeeded()
        }
    }

    private suspend fun loadProfiles() {
        mutableState.update { it.copy(loading = true) }
        try {
            val profiles = withTimeoutOrNull(20_000L) { repository.profiles() }
                ?: error("Profiilide laadimine aegus")
            val remembered = preferences.selectedProfile()
            val selected = profiles.firstOrNull { it.id == remembered }
                ?: profiles.singleOrNull()
            if (selected != null && selected.id != remembered) {
                preferences.saveSelectedProfile(selected.id)
            }
            mutableState.update { it.copy(profiles = profiles, selectedProfileId = selected?.id, loading = false) }
            if (selected != null) refreshPackage(selected.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            showError(error)
            mutableState.update { it.copy(loading = false) }
        }
    }

    /** Laeb kanalid ja telekava; vead näidatakse ainult siis, kui vahemälust pole midagi näidata või [errorsOnlyWhenEmpty] on väljas. */
    private suspend fun refreshPackage(profileId: String, errorsOnlyWhenEmpty: Boolean = false) {
        try {
            repository.refresh(profileId)
            lastGuideRefreshAt = Instant.now()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!errorsOnlyWhenEmpty || snapshot.channels.isEmpty()) showError(error)
        }
    }

    /** Taustal jooksev telekava värskendus, et pikalt lahti olev rakendus ei jääks vana kavaga. */
    private suspend fun refreshGuideIfStale() {
        val profileId = snapshot.selectedProfileId ?: return
        if (wasBackgrounded || snapshot.channels.isEmpty()) return
        val last = lastGuideRefreshAt
        if (last != null && Duration.between(last, Instant.now()) < GUIDE_REFRESH_INTERVAL) return
        runCatching { repository.refreshPrograms(profileId) }
            .onSuccess { lastGuideRefreshAt = Instant.now() }
            .onFailure { if (it is Go3Failure.Authentication) showError(it) }
    }

    // ---------------------------------------------------------------- key routing

    fun handleKey(event: KeyEvent): Boolean {
        if (guideLongPressHandled && event.keyCode.isConfirmKey()) {
            if (event.action == KeyEvent.ACTION_UP) {
                guideLongPressHandled = false
                guideOkJob = null
            }
            return true
        }
        val current = snapshot
        if (current.auth != DeviceAuthState.Approved && event.keyCode.isConfirmKey()) {
            if (
                event.isFirstPress &&
                (current.auth == DeviceAuthState.Idle ||
                    current.auth == DeviceAuthState.Expired ||
                    current.auth is DeviceAuthState.Failed)
            ) {
                startPairing()
            }
            return true
        }
        if (current.error != null) {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> mutableState.update { it.copy(errorActionIndex = 0) }
                KeyEvent.KEYCODE_DPAD_RIGHT -> mutableState.update { it.copy(errorActionIndex = 1) }
                in CONFIRM_KEYS -> if (current.errorActionIndex == 0) retry() else clearError()
                KeyEvent.KEYCODE_BACK -> clearError()
            }
            return true
        }
        if (current.overlay == Overlay.GUIDE && event.keyCode.isConfirmKey()) {
            return handleGuideConfirm(event)
        }
        if (current.overlay == Overlay.GUIDE && event.keyCode in PROGRAM_COLOR_KEYS) {
            if (event.isFirstPress) handleGuideColorKey(event.keyCode)
            return true
        }
        // Sama värvinupp, mis paneeli avas, sulgeb selle ka.
        if (COLOR_KEY_OVERLAYS[event.keyCode] == current.overlay) {
            if (event.isFirstPress) closeOverlay()
            return true
        }
        if (
            event.keyCode in PROGRAM_COLOR_KEYS &&
            current.overlay == Overlay.NONE && current.numberInput.isEmpty() && current.currentChannelId != null
        ) {
            if (event.isFirstPress) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_PROG_RED -> openTonight()
                    KeyEvent.KEYCODE_PROG_GREEN -> openTransit()
                    KeyEvent.KEYCODE_PROG_YELLOW -> openWeather()
                    KeyEvent.KEYCODE_PROG_BLUE -> toggleClock()
                }
            }
            return true
        }
        digitFor(event.keyCode)?.let { digit ->
            handleDigit(event, digit, current)
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (current.overlay == Overlay.NONE && current.numberInput.isEmpty()) return false
            closeOverlay()
            return true
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
        return when (current.overlay) {
            Overlay.GUIDE -> handleGuideKey(event.keyCode)
            Overlay.CHANNEL_RAIL -> handleRailKey(event.keyCode)
            Overlay.APP_SETTINGS -> handleAppSettingsKey(event.keyCode)
            Overlay.CHANNEL_SETTINGS -> handleChannelSettingsKey(event.keyCode)
            Overlay.PROFILE_SETTINGS -> handleProfileSettingsKey(event.keyCode)
            Overlay.LANGUAGE_SETTINGS -> handleLanguageSettingsKey(event.keyCode)
            Overlay.LOCATIONS_SETTINGS -> handleLocationsKey(event.keyCode)
            Overlay.SEA_SETTINGS -> handleSeaSettingsKey(event.keyCode)
            Overlay.SEA_STATION_PICKER -> handleSeaStationKey(event.keyCode)
            Overlay.DISPLAY_SETTINGS -> handleDisplaySettingsKey(event.keyCode)
            Overlay.WEATHER_LOCATION -> handleWeatherLocationKey(event.keyCode)
            Overlay.WEATHER -> handleWeatherKey(event.keyCode)
            Overlay.TRANSIT_STOP_SETTINGS -> handleTransitStopSettingsKey(event.keyCode)
            Overlay.TRANSIT -> handleTransitKey(event.keyCode)
            Overlay.TONIGHT -> handleTonightKey(event.keyCode)
            Overlay.SEEK -> handleSeekKey(event.keyCode)
            Overlay.NONE -> handlePlayerKey(event.keyCode)
        }
    }

    /** Sulgeb aktiivse paneeli: alammenüü naaseb vanemale, ülejäänud pildile. */
    private fun closeOverlay() {
        val current = snapshot
        if (current.overlay == Overlay.SEEK) {
            seekUiJob?.cancel()
            seekCloseJob?.cancel()
        }
        if (current.overlay == Overlay.TRANSIT || current.overlay == Overlay.TONIGHT) overlayRefreshJob?.cancel()
        val returnOverlay = SettingsNavigation.parent(current.overlay, current.settingsReturnOverlay)
        mutableState.update {
            it.copy(
                overlay = returnOverlay,
                numberInput = "",
                weather = it.weather.copy(error = null),
                transit = it.transit.copy(loading = false, error = null),
            )
        }
    }

    private fun handleDigit(event: KeyEvent, digit: Int, current: TvUiState) {
        if (event.action == KeyEvent.ACTION_UP) {
            heldDigitKey = null
            return
        }
        if (event.action != KeyEvent.ACTION_DOWN) return
        if (RemoteShortcutResolver.usesPreviousChannel(digit, current.numberInput, current.overlay)) {
            if (event.repeatCount == 0) tunePreviousChannel()
            return
        }
        val editingChannelNumber = current.overlay == Overlay.CHANNEL_SETTINGS
        if (event.repeatCount == 0) {
            appendDigit(digit, editingChannelNumber)
        } else if (!editingChannelNumber && heldDigitKey != event.keyCode) {
            heldDigitKey = event.keyCode
            digitJob?.cancel()
            mutableState.update { it.copy(numberInput = "") }
            tuneToNumber(digit)
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
        in CONFIRM_KEYS -> {
            openSeekOverlay()
            true
        }
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
            tvPlayer.togglePlayPause()
            true
        }
        else -> false
    }

    // ---------------------------------------------------------------- seek overlay

    private fun openSeekOverlay() {
        updateSeekState(tvPlayer.seekSnapshot(), Overlay.SEEK)
        scheduleSeekClose()
        seekUiJob?.cancel()
        seekUiJob = viewModelScope.launch {
            while (snapshot.overlay == Overlay.SEEK) {
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
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
            val forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            val stepMs = snapshot.seekStepSeconds * 1_000L
            val before = tvPlayer.seekSnapshot()
            val next = tvPlayer.seekBy(if (forward) stepMs else -stepMs)
            if (before.isLive) {
                manuallyTimeShifted = !forward || (next.liveOffsetMs ?: Long.MAX_VALUE) > 5_000L
            }
            updateSeekState(next, Overlay.SEEK)
            scheduleSeekClose()
            true
        }
        in CONFIRM_KEYS, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
            tvPlayer.togglePlayPause()
            updateSeekState(tvPlayer.seekSnapshot(), Overlay.SEEK)
            scheduleSeekClose()
            true
        }
        else -> false
    }

    private fun startViewedProgramFromBeginning() {
        val current = snapshot
        if (!current.seek.isLive || current.catchupProgram != null) {
            showNotice("Algusest alustamine on saadaval otseülekande ajal")
            return
        }
        val channelId = current.currentChannelId ?: return
        val playbackInstant = Instant.now().minusMillis(current.seek.liveOffsetMs?.coerceAtLeast(0L) ?: 0L)
        val program = nowProgram(channelId, playbackInstant)
        if (program == null) {
            showNotice("Seda saadet ei saa algusest alustada")
            return
        }
        val liveRewindMs = StartOverResolver.liveRewindMs(
            seekPositionMs = current.seek.positionMs,
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
        val show = !snapshot.showClock
        mutableState.update { it.copy(showClock = show) }
        if (showConfirmation) showNotice(if (show) "Kell sees" else "Kell väljas")
        viewModelScope.launch { preferences.saveShowClock(show) }
    }

    private fun updateSeekState(seek: SeekSnapshot, overlay: Overlay? = null) {
        mutableState.update {
            it.copy(
                overlay = overlay ?: it.overlay,
                seek = SeekState(
                    positionMs = seek.positionMs,
                    durationMs = seek.durationMs,
                    liveOffsetMs = seek.liveOffsetMs,
                    isLive = seek.isLive,
                    playing = seek.isPlaying,
                ),
            )
        }
    }

    private fun scheduleSeekClose() {
        seekCloseJob?.cancel()
        seekCloseJob = viewModelScope.launch {
            delay(snapshot.seekOverlaySeconds * 1_000L)
            if (snapshot.overlay == Overlay.SEEK) {
                mutableState.update { it.copy(overlay = Overlay.NONE) }
                seekUiJob?.cancel()
            }
        }
    }

    // ---------------------------------------------------------------- channel rail

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
        in CONFIRM_KEYS -> {
            snapshot.visibleChannels.getOrNull(snapshot.railIndex)?.let(::tune)
            mutableState.update { it.copy(overlay = Overlay.NONE) }
            true
        }
        else -> false
    }

    private fun showRail() {
        val current = snapshot
        val index = current.visibleChannels.indexOfFirst { it.id == current.currentChannelId }.coerceAtLeast(0)
        mutableState.update { it.copy(overlay = Overlay.CHANNEL_RAIL, railIndex = index) }
        scheduleRailClose()
    }

    private fun toggleRailFavorites() {
        val current = snapshot
        val favoritesOnly = !current.favoritesOnly
        if (favoritesOnly && current.favoriteChannelIds.isEmpty()) return
        val toggled = current.copy(favoritesOnly = favoritesOnly)
        val selected = toggled.visibleChannels.indexOfFirst { it.id == current.currentChannelId }.coerceAtLeast(0)
        mutableState.value = toggled.copy(railIndex = selected)
        scheduleRailClose()
    }

    private fun channelStep(delta: Int, immediate: Boolean) {
        val current = snapshot
        val channels = if (current.overlay == Overlay.CHANNEL_RAIL) current.visibleChannels else current.channels
        if (channels.isEmpty()) return
        val base = if (current.overlay == Overlay.CHANNEL_RAIL) current.railIndex
        else channels.indexOfFirst { it.id == current.currentChannelId }.coerceAtLeast(0)
        val next = Math.floorMod(base + delta, channels.size)
        mutableState.update { it.copy(overlay = Overlay.CHANNEL_RAIL, railIndex = next) }
        if (immediate) {
            channelTuneJob?.cancel()
            channelTuneJob = viewModelScope.launch {
                delay(CHANNEL_TUNE_DEBOUNCE_MS)
                channelTuneJob = null
                snapshot.visibleChannels.getOrNull(snapshot.railIndex)?.let(::tune)
            }
        }
        scheduleRailClose()
    }

    private fun scheduleRailClose() {
        railJob?.cancel()
        railJob = viewModelScope.launch {
            delay(snapshot.channelInfoSeconds * 1_000L)
            if (snapshot.overlay == Overlay.CHANNEL_RAIL) {
                mutableState.update { it.copy(overlay = Overlay.NONE) }
            }
        }
    }

    // ---------------------------------------------------------------- guide

    private fun handleGuideKey(keyCode: Int): Boolean {
        val current = snapshot
        val channels = current.visibleChannels
        if (channels.isEmpty()) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val next = stepIndex(current.guideChannelIndex, if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1, channels.size)
                mutableState.value = current.copy(
                    guideChannelIndex = next,
                    guideProgramIndex = guideProgramIndexAt(channels, next, current.guideAnchor ?: Instant.now()),
                )
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val channelPrograms = programsForGuideChannel(current)
                val next = stepIndex(current.guideProgramIndex, if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1, channelPrograms.size)
                val nextProgram = channelPrograms.getOrNull(next)
                mutableState.value = current.copy(
                    guideProgramIndex = next,
                    guideAnchor = nextProgram?.startsAt ?: current.guideAnchor,
                    guideWindowStart = nextProgram?.let {
                        ProgramWindow.guideWindowStartKeepingVisible(
                            current.guideWindowStart
                                ?: ProgramWindow.guideWindowStart(current.guideAnchor ?: Instant.now(), ZoneId.systemDefault()),
                            it,
                        )
                    } ?: current.guideWindowStart,
                )
            }
            else -> return false
        }
        return true
    }

    private fun handleGuideColorKey(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_PROG_RED -> jumpGuideDay(-1)
            KeyEvent.KEYCODE_PROG_GREEN -> jumpGuideDay(1)
            KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_PROG_BLUE -> toggleScheduledProgramAction(
                programsForGuideChannel(snapshot).getOrNull(snapshot.guideProgramIndex),
                toggleReminder = keyCode == KeyEvent.KEYCODE_PROG_YELLOW,
            )
        }
    }

    /** Ühine meeldetuletuse/automaatlülituse lüliti telekavale ja õhtukava paneelile. */
    private fun toggleScheduledProgramAction(program: Program?, toggleReminder: Boolean) {
        if (program == null || !program.startsAt.isAfter(Instant.now())) {
            showNotice(
                if (toggleReminder) "Meeldetuletuse saab lisada tulevasele saatele"
                else "Automaatlülituse saab lisada tulevasele saatele",
            )
            return
        }
        val previous = scheduledProgramActions[program.id]
        val updated = ScheduledProgramAction(
            programId = program.id,
            channelId = program.channelId,
            startsAtEpochMs = program.startsAt.toEpochMilli(),
            reminder = if (toggleReminder) previous?.reminder != true else previous?.reminder == true,
            autoTune = if (toggleReminder) previous?.autoTune == true else previous?.autoTune != true,
        ).takeIf { it.reminder || it.autoTune }

        scheduledProgramActions = scheduledProgramActions.toMutableMap().apply {
            if (updated == null) remove(program.id) else put(program.id, updated)
        }
        shownReminderIds.remove(program.id)
        publishScheduledProgramActions()
        viewModelScope.launch { preferences.saveScheduledProgramActions(scheduledProgramActions.values) }
        val message = when {
            updated == null -> "${program.title}: toiming eemaldatud"
            updated.reminder && updated.autoTune -> "${program.title}: meeldetuletus ja automaatlülitus"
            updated.autoTune -> "${program.title}: automaatlülitus"
            else -> "${program.title}: meeldetuletus"
        }
        showNotice(message)
    }

    private fun publishScheduledProgramActions() {
        mutableState.update {
            it.copy(
                scheduledReminderIds = scheduledProgramActions.values
                    .filter(ScheduledProgramAction::reminder)
                    .mapTo(mutableSetOf(), ScheduledProgramAction::programId),
                scheduledAutoTuneIds = scheduledProgramActions.values
                    .filter(ScheduledProgramAction::autoTune)
                    .mapTo(mutableSetOf(), ScheduledProgramAction::programId),
            )
        }
    }

    private suspend fun runProgramActionScheduler() {
        while (true) {
            val now = System.currentTimeMillis()
            val dueReminders = scheduledProgramActions.values.filter {
                it.reminder && it.programId !in shownReminderIds &&
                    now >= it.startsAtEpochMs - PROGRAM_REMINDER_LEAD_MS && now < it.startsAtEpochMs
            }
            if (!wasBackgrounded) {
                dueReminders.forEach { action ->
                    shownReminderIds += action.programId
                    showNotice("${scheduledProgramTitle(action)} algab ühe minuti pärast")
                }
            }

            val dueActions = scheduledProgramActions.values.filter {
                now >= it.startsAtEpochMs && now <= it.startsAtEpochMs + PROGRAM_ACTION_GRACE_MS
            }
            if (!wasBackgrounded && dueActions.isNotEmpty()) {
                dueActions.forEach { action ->
                    if (action.autoTune) {
                        snapshot.channels.firstOrNull { it.id == action.channelId }?.let(::tune)
                    }
                    showNotice(
                        if (action.autoTune) "${scheduledProgramTitle(action)} algas — lülitan kanalile"
                        else "${scheduledProgramTitle(action)} algas",
                    )
                }
                removeScheduledProgramActions(dueActions.mapTo(mutableSetOf(), ScheduledProgramAction::programId))
            }

            val expiredIds = scheduledProgramActions.values
                .filter { now > it.startsAtEpochMs + PROGRAM_ACTION_GRACE_MS }
                .mapTo(mutableSetOf(), ScheduledProgramAction::programId)
            if (expiredIds.isNotEmpty()) removeScheduledProgramActions(expiredIds)
            delay(PROGRAM_ACTION_POLL_MS)
        }
    }

    private fun scheduledProgramTitle(action: ScheduledProgramAction): String =
        snapshot.programsByChannel[action.channelId].orEmpty().firstOrNull { it.id == action.programId }?.title ?: "Saade"

    private suspend fun removeScheduledProgramActions(programIds: Set<String>) {
        scheduledProgramActions = scheduledProgramActions - programIds
        shownReminderIds.removeAll(programIds)
        publishScheduledProgramActions()
        preferences.saveScheduledProgramActions(scheduledProgramActions.values)
    }

    private fun showNotice(message: String) {
        mutableState.update { it.copy(notice = message) }
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(NOTICE_TIMEOUT_MS)
            if (snapshot.notice == message) mutableState.update { it.copy(notice = null) }
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
        val current = snapshot
        val channels = current.visibleChannels
        if (channels.isEmpty()) return
        val target = (current.guideAnchor ?: Instant.now()).plus(Duration.ofHours(24L * direction))
        val programIndex = guideProgramIndexAt(channels, current.guideChannelIndex, target)
        val resolvedStart = current.programsFor(channels.getOrNull(current.guideChannelIndex)?.id)
            .getOrNull(programIndex)?.startsAt
        mutableState.value = current.copy(
            guideProgramIndex = programIndex,
            guideAnchor = resolvedStart ?: target,
            guideWindowStart = ProgramWindow.guideWindowStart(resolvedStart ?: target, ZoneId.systemDefault()),
        )
    }

    private fun toggleGuideFavorites() {
        if (guideLongPressHandled) return
        guideOkJob?.cancel()
        guideLongPressHandled = true
        val current = snapshot
        val toggled = current.copy(favoritesOnly = !current.favoritesOnly)
        val targetChannels = toggled.visibleChannels
        if (targetChannels.isEmpty()) return
        val selectedId = current.visibleChannels.getOrNull(current.guideChannelIndex)?.id
        val targetIndex = targetChannels.indexOfFirst { it.id == selectedId }
            .takeIf { it >= 0 }
            ?: targetChannels.indexOfFirst { it.id == current.currentChannelId }.coerceAtLeast(0)
        mutableState.value = toggled.copy(
            guideChannelIndex = targetIndex,
            guideProgramIndex = guideProgramIndexAt(targetChannels, targetIndex, current.guideAnchor ?: Instant.now()),
        )
    }

    private fun activateGuideSelection() {
        val current = snapshot
        val program = programsForGuideChannel(current).getOrNull(current.guideProgramIndex)
        val channel = current.visibleChannels.getOrNull(current.guideChannelIndex)
        when (ProgramWindow.guideSelectionAction(program, Instant.now())) {
            ProgramWindow.GuideSelectionAction.PLAY_CATCHUP -> program?.let(::playCatchup)
            ProgramWindow.GuideSelectionAction.TUNE_LIVE -> if (channel != null) {
                mutableState.update { it.copy(overlay = Overlay.NONE, error = null, errorActionIndex = 0) }
                tune(channel)
            }
            ProgramWindow.GuideSelectionAction.SHOW_INFO -> if (program != null) {
                // A future programme is informational, not a playback failure.
                // Keep the guide and its description visible instead of showing
                // the global retry/error banner.
                mutableState.update { it.copy(error = null, errorActionIndex = 0) }
                showNotice(program.description?.takeIf(String::isNotBlank) ?: "${program.title} pole veel alanud")
            }
        }
    }

    private fun programsForGuideChannel(current: TvUiState): List<Program> =
        current.programsFor(current.visibleChannels.getOrNull(current.guideChannelIndex)?.id)

    private fun toggleGuide() {
        val current = snapshot
        val now = Instant.now()
        val favoritesActive = current.favoritesOnly && current.favoriteChannelIds.isNotEmpty()
        val toggled = current.copy(favoritesOnly = favoritesActive)
        val channels = toggled.visibleChannels
        val currentIndex = channels.indexOfFirst { it.id == current.currentChannelId }.coerceAtLeast(0)
        mutableState.value = toggled.copy(
            overlay = if (current.overlay == Overlay.GUIDE) Overlay.NONE else Overlay.GUIDE,
            guideChannelIndex = currentIndex,
            guideProgramIndex = guideProgramIndexAt(channels, currentIndex, now),
            guideAnchor = now,
            guideWindowStart = ProgramWindow.guideWindowStart(now, ZoneId.systemDefault()),
            error = null,
        )
    }

    private fun guideProgramIndexAt(
        channels: List<Channel>,
        channelIndex: Int,
        anchor: Instant,
        programsByChannel: Map<String, List<Program>> = snapshot.programsByChannel,
    ): Int {
        val channelId = channels.getOrNull(channelIndex)?.id ?: return 0
        val programs = programsByChannel[channelId].orEmpty()
        val current = programs.indexOfFirst { ProgramWindow.isCurrent(it, anchor) }
        if (current >= 0) return current
        val next = programs.indexOfFirst { !it.startsAt.isBefore(anchor) }
        return if (next >= 0) next else programs.lastIndex.coerceAtLeast(0)
    }

    // ---------------------------------------------------------------- number entry

    private fun appendDigit(number: Int, editingChannelNumber: Boolean) {
        val previous = snapshot.numberInput
        val next = (if (previous.length >= 3) "" else previous) + number
        mutableState.update { it.copy(numberInput = next, overlay = if (editingChannelNumber) it.overlay else Overlay.NONE) }
        digitJob?.cancel()
        digitJob = viewModelScope.launch {
            delay(2_000)
            if (editingChannelNumber) commitSettingsNumber() else tuneToNumber(snapshot.numberInput.toIntOrNull())
        }
    }

    private fun tuneToNumber(number: Int?) {
        mutableState.update { it.copy(numberInput = "") }
        ChannelNumberResolver.resolve(snapshot.channels, number)
            ?.let(::tune)
            ?: mutableState.update { it.copy(error = "Kanalit $number ei leitud") }
    }

    private fun commitSettingsNumber() {
        val current = snapshot
        val channel = current.channels.getOrNull(current.menuIndex) ?: return
        val number = current.numberInput.toIntOrNull()
        mutableState.update { it.copy(numberInput = "") }
        if (number == null || number !in 1..999) {
            mutableState.update { it.copy(error = "Kanalinumber peab olema vahemikus 1–999") }
            return
        }
        assignChannelNumber(channel, number)
    }

    private fun tunePreviousChannel() {
        val target = snapshot.channels.firstOrNull { it.id == previousChannelId }
        if (target == null) {
            showNotice("Eelmist kanalit pole veel")
            return
        }
        railJob?.cancel()
        seekUiJob?.cancel()
        seekCloseJob?.cancel()
        pendingSeekOverlayChannelId = target.id
        mutableState.update { it.copy(overlay = Overlay.NONE, numberInput = "") }
        tune(target)
    }

    // ---------------------------------------------------------------- settings menus

    private fun openAppSettings() {
        mutableState.update {
            it.copy(overlay = Overlay.APP_SETTINGS, appSettingsIndex = 0, numberInput = "", error = null)
        }
        if (snapshot.profiles.isEmpty()) {
            viewModelScope.launch {
                runCatching { repository.profiles() }
                    .onSuccess { profiles -> mutableState.update { it.copy(profiles = profiles) } }
            }
        }
    }

    private fun handleAppSettingsKey(keyCode: Int): Boolean = handleListKey(
        keyCode,
        index = snapshot.appSettingsIndex,
        count = AppSetting.entries.size,
        acceptRight = true,
        select = { index -> mutableState.update { it.copy(appSettingsIndex = index) } },
    ) {
        when (AppSetting.entries[snapshot.appSettingsIndex]) {
            AppSetting.PROFILE -> openProfileSettings()
            AppSetting.CHANNELS -> openChannelSettings()
            AppSetting.LANGUAGES -> openSubMenu(Overlay.LANGUAGE_SETTINGS, index = 0)
            AppSetting.DISPLAY -> openSubMenu(Overlay.DISPLAY_SETTINGS, index = 0)
            AppSetting.LOCATIONS -> {
                openSubMenu(Overlay.LOCATIONS_SETTINGS, index = 0)
                mutableState.update { it.copy(locationsIndex = 0) }
            }
            AppSetting.REFRESH_PACKAGE -> refreshChannelPackage()
        }
    }

    /** Avab seadete alammenüü, mis BACK-iga naaseb pealoendisse. */
    private fun openSubMenu(overlay: Overlay, index: Int) {
        mutableState.update { it.copy(overlay = overlay, menuIndex = index, settingsReturnOverlay = Overlay.APP_SETTINGS) }
    }

    private fun openProfileSettings() {
        val current = snapshot
        openSubMenu(Overlay.PROFILE_SETTINGS, current.profiles.indexOfFirst { it.id == current.selectedProfileId }.coerceAtLeast(0))
        viewModelScope.launch {
            runCatching { repository.profiles() }
                .onSuccess { profiles ->
                    val selected = profiles.indexOfFirst { it.id == snapshot.selectedProfileId }.coerceAtLeast(0)
                    mutableState.update {
                        it.copy(profiles = profiles, menuIndex = if (it.overlay == Overlay.PROFILE_SETTINGS) selected else it.menuIndex)
                    }
                }
                .onFailure(::showError)
        }
    }

    private fun handleProfileSettingsKey(keyCode: Int): Boolean = handleListKey(
        keyCode,
        index = snapshot.menuIndex,
        count = snapshot.profiles.size,
        select = ::selectMenuIndex,
    ) {
        snapshot.profiles.getOrNull(snapshot.menuIndex)?.let(::switchProfile)
    }

    private fun switchProfile(profile: Profile) {
        if (profile.id == snapshot.selectedProfileId) {
            mutableState.update { it.copy(overlay = Overlay.NONE) }
            return
        }
        tuneJob?.cancel()
        previousChannelId = null
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    selectedProfileId = profile.id,
                    currentChannelId = null,
                    overlay = Overlay.NONE,
                    loading = true,
                    error = null,
                )
            }
            repository.closePlayback(activeTicket?.playbackSessionId)
            activeTicket = null
            preferences.saveSelectedProfile(profile.id)
            refreshPackage(profile.id)
            mutableState.update { it.copy(loading = false) }
        }
    }

    private fun handleLanguageSettingsKey(keyCode: Int): Boolean {
        val current = snapshot
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            selectMenuIndex(stepIndex(current.menuIndex, if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1, LanguageSetting.entries.size))
            return true
        }
        if (keyCode !in CONFIRM_KEYS && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false
        val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
        val audio = if (LanguageSetting.entries[current.menuIndex] == LanguageSetting.AUDIO)
            cycleOption(AUDIO_LANGUAGE_OPTIONS.map { it.first }, current.audioLanguagePreference, direction)
        else current.audioLanguagePreference
        val subtitle = if (LanguageSetting.entries[current.menuIndex] == LanguageSetting.SUBTITLE)
            cycleOption(SUBTITLE_LANGUAGE_OPTIONS.map { it.first }, current.subtitleLanguagePreference, direction)
        else current.subtitleLanguagePreference
        tvPlayer.applyTrackPreferences(audio, subtitle)
        mutableState.update {
            it.copy(audioLanguagePreference = audio, subtitleLanguagePreference = subtitle)
        }
        viewModelScope.launch {
            preferences.savePreferredAudio(audio)
            preferences.savePreferredSubtitle(subtitle)
        }
        return true
    }

    private fun handleLocationsKey(keyCode: Int): Boolean = handleListKey(
        keyCode, snapshot.locationsIndex, LocationSetting.entries.size,
        acceptRight = true,
        select = { index -> mutableState.update { it.copy(locationsIndex = index) } },
    ) {
        when (LocationSetting.entries[snapshot.locationsIndex]) {
            LocationSetting.WEATHER -> openWeatherLocationSettings()
            LocationSetting.TRANSIT -> openTransitStopSettings()
            LocationSetting.SEA -> mutableState.update {
                it.copy(overlay = Overlay.SEA_SETTINGS, seaSettings = it.seaSettings.copy(menuIndex = 0))
            }
        }
    }

    private fun handleSeaSettingsKey(keyCode: Int): Boolean {
        val current = snapshot.seaSettings
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> mutableState.update {
                it.copy(seaSettings = it.seaSettings.copy(menuIndex = stepIndex(current.menuIndex, if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1, 3)))
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, in CONFIRM_KEYS -> {
                if (current.menuIndex == 2) {
                    saveSeaLocations(current.preferences.copy(
                        forecastPosition = current.preferences.forecastPosition.cycle(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1),
                    ))
                } else if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT) {
                    openSeaStationPicker(current.menuIndex == 1)
                }
            }
            else -> return false
        }
        return true
    }

    private fun openSeaStationPicker(second: Boolean) {
        val settings = snapshot.seaSettings
        val active = if (second) settings.preferences.second else settings.preferences.first
        mutableState.update {
            it.copy(overlay = Overlay.SEA_STATION_PICKER, seaSettings = it.seaSettings.copy(
                editingSecond = second,
                stationIndex = settings.stations.indexOfFirst { station -> station.stationName == active.stationName }.coerceAtLeast(0),
            ))
        }
        if (settings.stations.isEmpty()) loadSeaStations()
    }

    private fun loadSeaStations() {
        if (seaCatalogJob?.isActive == true) return
        seaCatalogJob = viewModelScope.launch {
            mutableState.update { it.copy(seaSettings = it.seaSettings.copy(loading = true, error = null)) }
            try {
                val stations = stationsGateway.stations()
                if (stations.isEmpty()) error("Jaamade nimekiri on tühi")
                mutableState.update {
                    val settings = it.seaSettings
                    val active = if (settings.editingSecond) settings.preferences.second else settings.preferences.first
                    it.copy(seaSettings = settings.copy(
                        stations = stations, loading = false,
                        stationIndex = stations.indexOfFirst { station -> station.stationName == active.stationName }.coerceAtLeast(0),
                    ))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(seaSettings = it.seaSettings.copy(loading = false, error = "Jaamade laadimine ebaõnnestus. OK proovib uuesti.")) }
            }
        }
    }

    private fun handleSeaStationKey(keyCode: Int): Boolean = handleListKey(
        keyCode, snapshot.seaSettings.stationIndex, snapshot.seaSettings.stations.size,
        select = { index -> mutableState.update { it.copy(seaSettings = it.seaSettings.copy(stationIndex = index)) } },
    ) {
        val settings = snapshot.seaSettings
        if (settings.stations.isEmpty()) loadSeaStations()
        else settings.stations.getOrNull(settings.stationIndex)?.let { point ->
            val other = if (settings.editingSecond) settings.preferences.first else settings.preferences.second
            if (point.stationName == other.stationName) {
                showNotice("See jaam on juba teiseks mõõtepunktiks valitud")
            } else {
                saveSeaLocations(if (settings.editingSecond) settings.preferences.copy(second = point) else settings.preferences.copy(first = point))
                mutableState.update { it.copy(overlay = Overlay.SEA_SETTINGS) }
            }
        }
    }

    private fun saveSeaLocations(value: SeaLocationPreferences) {
        seaForecastJob?.cancel()
        mutableState.update {
            it.copy(
                seaSettings = it.seaSettings.copy(preferences = value),
                weather = it.weather.copy(sea = null, seaLoading = false, seaError = null),
            )
        }
        viewModelScope.launch { preferences.saveSeaLocations(value) }
        // Forecasts are loaded on opening the weather panel; arrow presses stay instant.
    }

    private fun handleDisplaySettingsKey(keyCode: Int): Boolean {
        val current = snapshot
        val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                selectMenuIndex(stepIndex(current.menuIndex, if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1, DisplaySetting.entries.size))
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, in CONFIRM_KEYS -> when (DisplaySetting.entries[current.menuIndex]) {
                DisplaySetting.CLOCK -> toggleClock(showConfirmation = false)
                DisplaySetting.CHANNEL_INFO -> {
                    val seconds = DisplaySetting.CHANNEL_INFO.cycle(current.channelInfoSeconds, direction)
                    mutableState.update { it.copy(channelInfoSeconds = seconds) }
                    viewModelScope.launch { preferences.saveChannelInfoSeconds(seconds) }
                }
                DisplaySetting.SEEK_OVERLAY -> {
                    val seconds = DisplaySetting.SEEK_OVERLAY.cycle(current.seekOverlaySeconds, direction)
                    mutableState.update { it.copy(seekOverlaySeconds = seconds) }
                    viewModelScope.launch { preferences.saveSeekOverlaySeconds(seconds) }
                }
                DisplaySetting.SEEK_STEP -> {
                    val seconds = DisplaySetting.SEEK_STEP.cycle(current.seekStepSeconds, direction)
                    mutableState.update { it.copy(seekStepSeconds = seconds) }
                    viewModelScope.launch { preferences.saveSeekStepSeconds(seconds) }
                }
            }
            else -> return false
        }
        return true
    }

    private fun refreshChannelPackage() {
        val profileId = snapshot.selectedProfileId ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(overlay = Overlay.NONE, loading = true, error = null) }
            preferences.clearHiddenChannels(profileId)
            refreshPackage(profileId)
            mutableState.update { it.copy(loading = false) }
        }
    }

    private fun openChannelSettings() {
        val current = snapshot
        openSubMenu(Overlay.CHANNEL_SETTINGS, current.channels.indexOfFirst { it.id == current.currentChannelId }.coerceAtLeast(0))
        mutableState.update { it.copy(numberInput = "", error = null) }
    }

    private fun handleChannelSettingsKey(keyCode: Int): Boolean {
        val current = snapshot
        val channel = current.channels.getOrNull(current.menuIndex) ?: return false
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> mutableState.update {
                it.copy(
                    menuIndex = stepIndex(current.menuIndex, if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1, current.channels.size),
                    numberInput = "",
                )
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> assignChannelNumber(channel, channel.number - 1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> assignChannelNumber(channel, channel.number + 1)
            in CONFIRM_KEYS -> saveChannelSetting(channel, favorite = channel.id !in current.favoriteChannelIds)
            else -> return false
        }
        return true
    }

    private fun saveChannelSetting(channel: Channel, favorite: Boolean) {
        viewModelScope.launch {
            runCatching { preferences.saveChannelPreferences(listOf(ChannelPreference(channel.id, channel.number, favorite))) }
                .onSuccess {
                    mutableState.update { current ->
                        val favorites = current.favoriteChannelIds.toMutableSet().apply {
                            if (favorite) add(channel.id) else remove(channel.id)
                        }
                        current.copy(
                            favoriteChannelIds = favorites,
                            favoritesOnly = current.favoritesOnly && favorites.isNotEmpty(),
                            error = null,
                        )
                    }
                }
                .onFailure(::showError)
        }
    }

    private fun assignChannelNumber(channel: Channel, targetNumber: Int) {
        val current = snapshot
        val existing = current.channels.associate { it.id to it.number }
        val assignments = ChannelNumberResolver.assignWithShift(existing, channel.id, targetNumber)
        if (assignments == null) {
            mutableState.update { it.copy(error = "Kanalinumber peab olema vahemikus 1–999") }
            return
        }
        val channelPreferences = current.channels.map { item ->
            ChannelPreference(
                channelId = item.id,
                number = assignments[item.id] ?: item.number,
                favorite = item.id in current.favoriteChannelIds,
            )
        }
        val updatedChannels = current.channels
            .map { item -> item.copy(serverNumber = assignments[item.id] ?: item.serverNumber) }
            .sortedBy(Channel::number)
        mutableState.update {
            it.copy(
                channels = updatedChannels,
                menuIndex = updatedChannels.indexOfFirst { item -> item.id == channel.id }.coerceAtLeast(0),
                error = null,
            )
        }
        viewModelScope.launch {
            runCatching { preferences.saveChannelPreferences(channelPreferences) }.onFailure(::showError)
        }
    }

    private fun selectMenuIndex(index: Int) = mutableState.update { it.copy(menuIndex = index) }

    // ---------------------------------------------------------------- weather

    private inline fun updateWeather(block: WeatherState.() -> WeatherState) =
        mutableState.update { it.copy(weather = it.weather.block()) }

    private fun openWeather() {
        val current = snapshot.weather
        mutableState.update {
            it.copy(overlay = Overlay.WEATHER, settingsReturnOverlay = Overlay.NONE, weather = it.weather.copy(error = null, seaError = null))
        }
        val stale = current.forecast?.let { Duration.between(it.fetchedAt, Instant.now()) > WEATHER_MAX_AGE } != false
        if (stale) refreshWeather(current.location)
        val seaStale = current.sea?.let { Duration.between(it.fetchedAt, Instant.now()) > WEATHER_MAX_AGE } != false
        if (seaStale) refreshSea()
    }

    /** Vasak/parem vahetab ilma- ja merelehte; OK värskendab avatud lehe andmed. */
    private fun handleWeatherKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> updateWeather {
                copy(page = if (page == WeatherPage.WEATHER) WeatherPage.SEA else WeatherPage.WEATHER)
            }
            in CONFIRM_KEYS -> if (snapshot.weather.page == WeatherPage.SEA) refreshSea() else refreshWeather(snapshot.weather.location)
        }
        return true
    }

    private fun refreshSea() {
        if (snapshot.weather.seaLoading) return
        val route = snapshot.seaSettings.preferences.route()
        seaForecastJob = viewModelScope.launch {
            updateWeather { copy(seaLoading = true, seaError = null) }
            runCatching {
                coroutineScope {
                    val forecast = async { weatherGateway.seaForecast(route) }
                    // Jaamade XML on lisaväärtus; selle viga ei tohi kogu merelehte tühjaks jätta.
                    val observations = async { runCatching { stationsGateway.observations(route.stationNames) }.getOrDefault(emptyMap()) }
                    forecast.await().copy(
                        harbourObservation = observations.await()[route.harbour.stationName],
                        destinationObservation = observations.await()[route.destination.stationName],
                    )
                }
            }
                .onSuccess { sea ->
                    if (snapshot.seaSettings.preferences.route() == route)
                        updateWeather { copy(sea = sea, seaLoading = false, seaError = null) }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    if (snapshot.seaSettings.preferences.route() == route)
                        updateWeather { copy(seaLoading = false, seaError = "Mereilma värskendamine ebaõnnestus: ${error.message ?: "tundmatu viga"}") }
                }
        }
    }

    private fun openWeatherLocationSettings() {
        val location = snapshot.weather.location
        mutableState.update {
            it.copy(
                overlay = Overlay.WEATHER_LOCATION,
                settingsReturnOverlay = Overlay.LOCATIONS_SETTINGS,
                weather = it.weather.copy(error = null, search = SearchState(query = location.name, results = listOf(location))),
            )
        }
    }

    fun updateWeatherSearchQuery(query: String) = updateWeather { copy(search = search.withQuery(query)) }

    fun searchWeatherLocations() = runSearch(
        current = { snapshot.weather.search },
        publish = { search -> updateWeather { copy(search = search) } },
        fetch = { query -> weatherGateway.searchLocations(query).take(5) },
        emptyMessage = "Asulat ei leitud",
        failureMessage = "Asukoha otsing ebaõnnestus",
    )

    fun handleFocusedSearchKey(keyCode: Int) {
        when (snapshot.overlay) {
            Overlay.WEATHER_LOCATION -> handleWeatherLocationKey(keyCode)
            Overlay.TRANSIT_STOP_SETTINGS -> handleTransitStopSettingsKey(keyCode)
            else -> Unit
        }
    }

    private fun handleWeatherLocationKey(keyCode: Int): Boolean = handleSearchKey(
        keyCode,
        search = snapshot.weather.search,
        publish = { search -> updateWeather { copy(search = search) } },
        runSearch = ::searchWeatherLocations,
        select = ::selectWeatherLocation,
    )

    private fun selectWeatherLocation(location: WeatherLocation) {
        mutableState.update {
            it.copy(
                overlay = it.settingsReturnOverlay,
                weather = it.weather.copy(location = location, forecast = null, error = null, search = SearchState(query = location.name)),
            )
        }
        viewModelScope.launch { preferences.saveWeatherLocation(location) }
        refreshWeather(location)
    }

    private fun refreshWeather(location: WeatherLocation) {
        viewModelScope.launch {
            updateWeather { copy(loading = true, error = null) }
            runCatching { weatherGateway.forecast(location) }
                .onSuccess { forecast ->
                    updateWeather { if (this.location == location) copy(forecast = forecast, loading = false, error = null) else copy(loading = false) }
                }
                .onFailure { error ->
                    updateWeather { copy(loading = false, error = "Ilma värskendamine ebaõnnestus: ${error.message ?: "tundmatu viga"}") }
                }
        }
    }

    // ---------------------------------------------------------------- transit

    private inline fun updateTransit(block: TransitState.() -> TransitState) =
        mutableState.update { it.copy(transit = it.transit.block()) }

    private fun openTransit() {
        overlayRefreshJob?.cancel()
        mutableState.update {
            it.copy(
                overlay = Overlay.TRANSIT,
                settingsReturnOverlay = Overlay.NONE,
                transit = it.transit.copy(
                    error = null,
                    departureIndex = 0,
                    directionIndex = it.transit.directionIndex.coerceIn(0, it.transit.stop.platforms.lastIndex.coerceAtLeast(0)),
                ),
            )
        }
        startTransitRefreshLoop()
    }

    /** Värskendab kohe ja edaspidi iga minut, kuni paneel on lahti; valik jääb samale väljumisele. */
    private fun startTransitRefreshLoop() {
        overlayRefreshJob?.cancel()
        overlayRefreshJob = viewModelScope.launch {
            while (snapshot.overlay == Overlay.TRANSIT) {
                refreshTransitBoard()
                delay(TRANSIT_REFRESH_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshTransitBoard() {
        updateTransit { copy(loading = true, error = null) }
        runCatching { transitGateway.departures(snapshot.transit.stop) }
            .onSuccess { board ->
                if (snapshot.overlay != Overlay.TRANSIT) return@onSuccess
                updateTransit {
                    val selected = visibleDepartures.getOrNull(departureIndex)
                    val updated = copy(board = board, loading = false, error = null)
                    val refreshed = updated.visibleDepartures
                    val index = selected?.let { previous -> refreshed.indexOfFirst { it.sameTrip(previous) } }?.takeIf { it >= 0 }
                        ?: departureIndex.coerceIn(0, refreshed.lastIndex.coerceAtLeast(0))
                    updated.copy(departureIndex = index)
                }
            }
            .onFailure { error ->
                if (snapshot.overlay == Overlay.TRANSIT) {
                    updateTransit { copy(loading = false, error = "Bussiaegade värskendamine ebaõnnestus: ${error.message ?: "tundmatu viga"}") }
                }
            }
    }

    private fun handleTransitKey(keyCode: Int): Boolean {
        val transit = snapshot.transit
        val departureCount = transit.visibleDepartures.size
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> updateTransit {
                copy(
                    directionIndex = stepIndex(directionIndex, if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1, stop.platforms.size),
                    departureIndex = 0,
                )
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> updateTransit {
                copy(departureIndex = stepIndex(departureIndex, if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1, departureCount))
            }
            in CONFIRM_KEYS -> startTransitRefreshLoop()
        }
        return true
    }

    private fun openTransitStopSettings() {
        val stop = snapshot.transit.stop
        mutableState.update {
            it.copy(
                overlay = Overlay.TRANSIT_STOP_SETTINGS,
                settingsReturnOverlay = Overlay.LOCATIONS_SETTINGS,
                transit = it.transit.copy(search = SearchState(query = stop.name, results = listOf(stop))),
            )
        }
    }

    fun updateTransitStopSearchQuery(query: String) = updateTransit { copy(search = search.withQuery(query)) }

    fun searchTransitStops() = runSearch(
        current = { snapshot.transit.search },
        publish = { search -> updateTransit { copy(search = search) } },
        fetch = { query -> transitGateway.searchStops(query).take(6) },
        emptyMessage = "Peatust ei leitud",
        failureMessage = "Peatuse otsing ebaõnnestus",
    )

    private fun handleTransitStopSettingsKey(keyCode: Int): Boolean = handleSearchKey(
        keyCode,
        search = snapshot.transit.search,
        publish = { search -> updateTransit { copy(search = search) } },
        runSearch = ::searchTransitStops,
        select = ::selectTransitStop,
    )

    private fun selectTransitStop(stop: TransitStopSelection) {
        mutableState.update {
            it.copy(
                overlay = it.settingsReturnOverlay,
                transit = TransitState(stop = stop, search = SearchState(query = stop.name)),
            )
        }
        viewModelScope.launch { preferences.saveTransitStop(stop) }
    }

    // ---------------------------------------------------------------- tonight

    private fun openTonight() {
        overlayRefreshJob?.cancel()
        val now = Instant.now()
        mutableState.update {
            it.copy(
                overlay = Overlay.TONIGHT,
                settingsReturnOverlay = Overlay.NONE,
                tonight = TonightState(entries = tonightEntries(it, now), index = 0, now = now),
            )
        }
        overlayRefreshJob = viewModelScope.launch {
            while (snapshot.overlay == Overlay.TONIGHT) {
                delay(TONIGHT_REFRESH_INTERVAL_MS)
                refreshTonightEntries()
            }
        }
    }

    private fun tonightEntries(current: TvUiState, now: Instant): List<TonightEntry> = TonightScheduleResolver.entries(
        current.channels,
        current.favoriteChannelIds,
        current.programsByChannel,
        now,
        ZoneId.systemDefault(),
    )

    private fun refreshTonightEntries() {
        val current = snapshot
        if (current.overlay != Overlay.TONIGHT) return
        val now = Instant.now()
        val entries = tonightEntries(current, now)
        // Hoia valik samal saatel, kui nimekiri värskendusel nihkub.
        val selectedProgramId = current.tonight.entries.getOrNull(current.tonight.index)?.program?.id
        val index = entries.indexOfFirst { it.program.id == selectedProgramId }
            .takeIf { it >= 0 }
            ?: current.tonight.index.coerceIn(0, entries.lastIndex.coerceAtLeast(0))
        mutableState.update { it.copy(tonight = TonightState(entries, index, now)) }
    }

    private fun handleTonightKey(keyCode: Int): Boolean {
        val tonight = snapshot.tonight
        val entry = tonight.entries.getOrNull(tonight.index)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> mutableState.update {
                it.copy(tonight = it.tonight.copy(index = stepIndex(tonight.index, if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1, tonight.entries.size)))
            }
            in CONFIRM_KEYS -> {
                if (entry == null) return true
                val now = Instant.now()
                when {
                    // Tulevane saade: OK on kiirtee meeldetuletuseni.
                    entry.program.startsAt.isAfter(now) -> toggleScheduledProgramAction(entry.program, toggleReminder = true)
                    // Käimasolev saade: hüppa kohe kanalile.
                    entry.program.endsAt.isAfter(now) -> {
                        mutableState.update { it.copy(overlay = Overlay.NONE, error = null, errorActionIndex = 0) }
                        tune(entry.channel)
                    }
                    // Vahepeal lõppenud saade: paku järelvaatamist.
                    else -> playCatchup(entry.program)
                }
            }
            KeyEvent.KEYCODE_PROG_YELLOW -> entry?.let { toggleScheduledProgramAction(it.program, toggleReminder = true) }
            KeyEvent.KEYCODE_PROG_BLUE -> entry?.let { toggleScheduledProgramAction(it.program, toggleReminder = false) }
            else -> return false
        }
        return true
    }

    // ---------------------------------------------------------------- playback

    private fun tune(
        channel: Channel,
        wakeRecovery: Boolean = false,
        resetPlaybackRetry: Boolean = true,
    ) {
        val profileId = snapshot.selectedProfileId ?: return
        if (pendingSeekOverlayChannelId != null && pendingSeekOverlayChannelId != channel.id) {
            pendingSeekOverlayChannelId = null
        }
        channelTuneJob?.cancel()
        channelTuneJob = null
        playbackRetryJob?.cancel()
        playbackRetryJob = null
        overlayRefreshJob?.cancel()
        val generation = ++tuneGeneration
        tuneJob?.cancel()
        tuneJob = viewModelScope.launch {
            if (resetPlaybackRetry) retryCount = 0
            val keepPreviousVideo = snapshot.videoVisible && !wakeRecovery
            mutableState.update { it.copy(loading = true, videoVisible = keepPreviousVideo, error = null) }
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
                        currentChannelId = snapshot.currentChannelId,
                        tunedChannelId = channel.id,
                    )
                    mutableState.update { it.copy(currentChannelId = channel.id, catchupProgram = null) }
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
                    mutableState.update { it.copy(loading = false) }
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
        preferences.hideChannel(profileId, channel.id)
        val current = snapshot
        val removedIndex = current.channels.indexOfFirst { it.id == channel.id }.coerceAtLeast(0)
        val remainingChannels = current.channels.filterNot { it.id == channel.id }
        val remainingFavorites = current.favoriteChannelIds - channel.id
        mutableState.value = current.copy(
            channels = remainingChannels,
            programsByChannel = current.programsByChannel - channel.id,
            favoriteChannelIds = remainingFavorites,
            favoritesOnly = current.favoritesOnly && remainingFavorites.isNotEmpty(),
            currentChannelId = current.currentChannelId.takeUnless { it == channel.id },
            overlay = Overlay.NONE,
            error = null,
        )
        val fallback = remainingChannels.getOrNull(removedIndex.coerceAtMost(remainingChannels.lastIndex))
            ?: remainingChannels.firstOrNull()
        if (fallback != null) viewModelScope.launch { tune(fallback) }
    }

    private fun playCatchup(program: Program) {
        if (!program.catchupAvailable) {
            mutableState.update { it.copy(error = "Selle saate järelvaatamine pole saadaval") }
            return
        }
        val profileId = snapshot.selectedProfileId ?: return
        tuneJob?.cancel()
        tuneJob = viewModelScope.launch {
            val keepPreviousVideo = snapshot.videoVisible
            mutableState.update { it.copy(loading = true, videoVisible = keepPreviousVideo, error = null, overlay = Overlay.NONE) }
            if (!keepPreviousVideo) tvPlayer.stopAndClear()
            try {
                manuallyTimeShifted = true
                val previousSessionId = activeTicket?.playbackSessionId
                val (resolvedProgram, ticket) = catchupTicketWithRefresh(profileId, program)
                repository.closePlayback(previousSessionId)
                activeTicket = ticket
                scheduleProlong(ticket)
                pendingChannelId = resolvedProgram.channelId
                retryCount = 0
                val channelName = snapshot.channels.firstOrNull { it.id == resolvedProgram.channelId }?.name ?: "Go3 Air"
                mutableState.update { it.copy(catchupProgram = resolvedProgram) }
                tvPlayer.play(ticket, channelName, resolvedProgram.title)
            } catch (error: Exception) {
                showError(error)
            } finally {
                mutableState.update { it.copy(loading = false) }
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
            viewModelScope.launch { preferences.saveLastChannel(id) }
            pendingChannelId = null
        }
        mutableState.update { it.copy(loading = false, error = null) }
        schedulePlaybackHealthCheck()
    }

    override fun onFirstFrame() {
        retryCount = 0
        mutableState.update { it.copy(videoVisible = true, loading = false, error = null) }
        if (pendingSeekOverlayChannelId == snapshot.currentChannelId) {
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
            mutableState.update { it.copy(error = null, loading = true, videoVisible = false) }
            delay(delaySeconds * 1_000L)
            if (ticket.isLive) {
                val channel = currentChannel()
                playbackRetryJob = null
                if (channel != null) tune(channel, wakeRecovery = true, resetPlaybackRetry = false)
                else showError(error)
            } else {
                tvPlayer.play(ticket)
            }
        }
    }

    fun retry() {
        val channel = currentChannel()
        if (channel != null) {
            mutableState.update { it.copy(error = null, errorActionIndex = 0) }
            tune(channel, wakeRecovery = true)
            return
        }
        val profileId = snapshot.selectedProfileId
        mutableState.update { it.copy(error = null, errorActionIndex = 0, loading = true) }
        if (profileId == null) {
            startStartupRecovery()
            return
        }
        viewModelScope.launch {
            refreshPackage(profileId)
            mutableState.update { it.copy(loading = false) }
        }
    }

    fun clearError() = mutableState.update { it.copy(error = null, errorActionIndex = 0) }

    fun onAppBackgrounded() {
        if (wasBackgrounded) return
        wasBackgrounded = true
        startupRecoveryJob?.cancel()
        startupRecoveryJob = null
        tuneJob?.cancel()
        channelTuneJob?.cancel()
        channelTuneJob = null
        pendingSeekOverlayChannelId = null
        tuneGeneration++
        prolongJob?.cancel()
        seekUiJob?.cancel()
        seekCloseJob?.cancel()
        overlayRefreshJob?.cancel()
        playbackHealthJob?.cancel()
        noticeJob?.cancel()
        playbackRetryJob?.cancel()
        playbackRetryJob = null
        tvPlayer.stopAndClear()
        val sessionId = activeTicket?.playbackSessionId
        activeTicket = null
        mutableState.update {
            it.copy(overlay = Overlay.NONE, numberInput = "", videoVisible = false, loading = false, notice = null)
        }
        cleanupScope.launch { repository.closePlayback(sessionId) }
    }

    fun onAppForegrounded() {
        val current = snapshot
        val needsStartup = current.selectedProfileId == null || current.channels.isEmpty() || current.currentChannelId == null
        if (current.auth == DeviceAuthState.Approved && needsStartup) startStartupRecovery()
        if (!wasBackgrounded) return
        wasBackgrounded = false
        if (needsStartup) return
        val channel = currentChannel() ?: return
        tune(channel, wakeRecovery = true)
        // Let the first video segments through before checking whether the guide needs refreshing.
        viewModelScope.launch {
            delay(WAKE_GUIDE_REFRESH_DELAY_MS)
            refreshGuideIfStale()
        }
    }

    private fun currentChannel(): Channel? = snapshot.channels.firstOrNull { it.id == snapshot.currentChannelId }

    private fun scheduleProlong(ticket: PlaybackTicket) {
        prolongJob?.cancel()
        val sessionId = ticket.playbackSessionId ?: return
        val intervalSeconds = ticket.prolongIntervalSeconds?.coerceAtLeast(30) ?: return
        prolongJob = viewModelScope.launch {
            while (true) {
                delay(intervalSeconds * 1_000)
                runCatching { repository.prolongPlayback(sessionId) }.onFailure(::showError)
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
                    currentChannel()?.let { channel -> tvPlayer.updateNowPlaying(channel.name, nowProgram(channel.id)?.title) }
                }
                if (!manuallyTimeShifted) tvPlayer.correctLiveDriftIfNeeded()
            }
        }
    }

    private fun nowProgram(channelId: String, at: Instant = Instant.now()): Program? =
        snapshot.programsByChannel[channelId].orEmpty().firstOrNull { ProgramWindow.isCurrent(it, at) }

    private fun showError(error: Throwable) {
        if (error is Go3Failure.Authentication) {
            signOutAfterAuthFailure(error.message ?: "Sisselogimine aegus. Seo konto uuesti.")
            return
        }
        mutableState.update { it.copy(error = error.message ?: "Tundmatu viga", errorActionIndex = 0, loading = false) }
    }

    /** Go3 ei tunne tokenit enam; ainus tee edasi on uus sidumine, mille sidumisekraan ise pakub. */
    private fun signOutAfterAuthFailure(reason: String) {
        tuneJob?.cancel()
        prolongJob?.cancel()
        playbackHealthJob?.cancel()
        playbackRetryJob?.cancel()
        overlayRefreshJob?.cancel()
        tvPlayer.stopAndClear()
        activeTicket = null
        previousChannelId = null
        mutableState.update {
            it.copy(
                currentChannelId = null,
                catchupProgram = null,
                videoVisible = false,
                loading = false,
                overlay = Overlay.NONE,
                numberInput = "",
                error = null,
            )
        }
        authCoordinator.signOut(reason)
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
        overlayRefreshJob?.cancel()
        val sessionId = activeTicket?.playbackSessionId
        cleanupScope.launch { repository.closePlayback(sessionId) }
        tvPlayer.release()
        super.onCleared()
    }

    // ---------------------------------------------------------------- shared helpers

    /** Ühine üles/alla/OK loogika lihtsatele nimekirjamenüüdele. Tagastab false, kui nupp menüüsse ei kuulu. */
    private inline fun handleListKey(
        keyCode: Int,
        index: Int,
        count: Int,
        acceptRight: Boolean = false,
        select: (Int) -> Unit,
        confirm: () -> Unit,
    ): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> select(stepIndex(index, -1, count))
            KeyEvent.KEYCODE_DPAD_DOWN -> select(stepIndex(index, 1, count))
            in CONFIRM_KEYS -> confirm()
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (acceptRight) confirm() else return false
            else -> return false
        }
        return true
    }

    private fun <T> handleSearchKey(
        keyCode: Int,
        search: SearchState<T>,
        publish: (SearchState<T>) -> Unit,
        runSearch: () -> Unit,
        select: (T) -> Unit,
    ): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> publish(
                search.copy(
                    index = SearchSelectionResolver.move(search.index, search.results.size, if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1),
                ),
            )
            in CONFIRM_KEYS -> search.results.getOrNull(search.index)?.let(select) ?: runSearch()
            else -> return false
        }
        return true
    }

    private fun <T> runSearch(
        current: () -> SearchState<T>,
        publish: (SearchState<T>) -> Unit,
        fetch: suspend (String) -> List<T>,
        emptyMessage: String,
        failureMessage: String,
    ) {
        val query = current().query.trim()
        if (query.length < 2 || current().loading) return
        viewModelScope.launch {
            publish(current().copy(loading = true, error = null))
            runCatching { fetch(query) }
                .onSuccess { results ->
                    val latest = current()
                    publish(if (latest.query.trim() == query)
                        latest.copy(loading = false, results = results, index = -1, error = if (results.isEmpty()) emptyMessage else null)
                    else latest.copy(loading = false))
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    val latest = current()
                    publish(if (latest.query.trim() == query)
                        latest.copy(loading = false, error = "$failureMessage: ${error.message ?: "tundmatu viga"}")
                    else latest.copy(loading = false))
                }
        }
    }

    class Factory(private val container: AppContainer, private val player: TvPlayer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TvViewModel(
            container.auth,
            container.repository,
            container.preferences,
            container.weather,
            container.stations,
            container.transit,
            player,
            container.isDemo,
        ) as T
    }
}

private const val STARTUP_REFRESH_DEFER_MS = 5_000L
private const val CHANNEL_TUNE_DEBOUNCE_MS = 180L
private const val PROGRAM_REMINDER_LEAD_MS = 60_000L
private const val PROGRAM_ACTION_GRACE_MS = 5 * 60_000L
private const val PROGRAM_ACTION_POLL_MS = 15_000L
private const val NOTICE_TIMEOUT_MS = 5_000L
private const val TRANSIT_REFRESH_INTERVAL_MS = 60_000L
private const val TONIGHT_REFRESH_INTERVAL_MS = 30_000L
private const val GUIDE_REFRESH_CHECK_MS = 30 * 60_000L
private const val WAKE_GUIDE_REFRESH_DELAY_MS = 10_000L
private val GUIDE_REFRESH_INTERVAL: Duration = Duration.ofHours(6)
private val WEATHER_MAX_AGE: Duration = Duration.ofMinutes(15)
private val CONFIRM_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
private val PROGRAM_COLOR_KEYS = setOf(
    KeyEvent.KEYCODE_PROG_RED,
    KeyEvent.KEYCODE_PROG_GREEN,
    KeyEvent.KEYCODE_PROG_YELLOW,
    KeyEvent.KEYCODE_PROG_BLUE,
)
/** Täisekraanil avavad värvinupud paneeli; sama nupp paneeli sees sulgeb selle. */
private val COLOR_KEY_OVERLAYS = mapOf(
    KeyEvent.KEYCODE_PROG_RED to Overlay.TONIGHT,
    KeyEvent.KEYCODE_PROG_GREEN to Overlay.TRANSIT,
    KeyEvent.KEYCODE_PROG_YELLOW to Overlay.WEATHER,
)

private fun stepIndex(current: Int, delta: Int, count: Int): Int = (current + delta).coerceIn(0, (count - 1).coerceAtLeast(0))

private fun Int.isConfirmKey(): Boolean = this in CONFIRM_KEYS

private val KeyEvent.isFirstPress: Boolean get() = action == KeyEvent.ACTION_DOWN && repeatCount == 0

private fun digitFor(keyCode: Int): Int? = when (keyCode) {
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
    else -> null
}
