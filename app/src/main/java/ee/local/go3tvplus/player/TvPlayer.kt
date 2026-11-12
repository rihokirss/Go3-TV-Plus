@file:androidx.media3.common.util.UnstableApi

package ee.local.go3tvplus.player

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Format
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import ee.local.go3tvplus.BuildConfig
import ee.local.go3tvplus.domain.PlaybackTicket

data class SeekSnapshot(
    val positionMs: Long,
    val durationMs: Long,
    val liveOffsetMs: Long?,
    val isLive: Boolean,
    val isPlaying: Boolean,
)

class TvPlayer(context: Context) {
    private val appContext = context.applicationContext
    // This Realtek TV exposes a 60 Hz app display while broadcast video runs at
    // 25/50 fps. Use its supported audio/video hardware clock instead of app-vsync
    // frame scheduling. The selector falls back if either renderer lacks support.
    private val useHardwareAvSync = Build.MANUFACTURER.equals("TCL", ignoreCase = true) && Build.HARDWARE == "rtd6748"
    private val playbackDiagnostics = BuildConfig.DEBUG || useHardwareAvSync ||
        appContext.getSharedPreferences("local_diagnostics", Context.MODE_PRIVATE).getBoolean("playback", false)
    private val minLiveSpeed = if (useHardwareAvSync) 1f else 0.995f
    private val maxLiveSpeed = if (useHardwareAvSync) 1f else 1.005f
    private val trackSelector = DefaultTrackSelector(
        appContext,
        AdaptiveTrackSelection.Factory(
            /* minDurationForQualityIncreaseMs = */ 6_000,
            // Go3's ladder jumps from 1080p50 straight to 720p25. The Media3 default
            // (25 s) is usually above the available live-edge buffer, so even a short
            // bandwidth estimate dip changes frame cadence. Keep 50 fps while there is
            // a safe buffer, but still allow a downgrade before an actual rebuffer.
            /* maxDurationForQualityDecreaseMs = */ 8_000,
            /* minDurationToRetainAfterDiscardMs = */ 12_000,
            /* bandwidthFraction = */ 0.82f,
        ),
    ).apply {
        setParameters(
            buildUponParameters()
                // Go3's sports ladder has 50 fps only at 1080p/8 Mbps. Mixing it with
                // the 25 fps fallback causes severe cadence changes on some TV codecs.
                // If a channel has no >=48 fps representation, the explicit exceed
                // fallback keeps its normal 25 fps tracks playable.
                .setMinVideoFrameRate(MIN_SMOOTH_VIDEO_FRAME_RATE)
                .setExceedVideoConstraintsIfNecessary(true)
                .setTunnelingEnabled(useHardwareAvSync),
        )
    }
    private val liveSpeedControl = DefaultLivePlaybackSpeedControl.Builder()
        // Wide speed changes are visible as judder on 25/50 fps TV channels.
        .setFallbackMinPlaybackSpeed(minLiveSpeed)
        .setFallbackMaxPlaybackSpeed(maxLiveSpeed)
        .setMaxLiveOffsetErrorMsForUnitSpeed(2_000)
        .setMinUpdateIntervalMs(2_000)
        .build()
    // Bound compressed video memory while retaining ~24 s at Go3's 8 Mbps 1080p50.
    // Metadata and decoded posters share the 192 MB heap on older Android TVs.
    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(15_000, 30_000, 1_500, 3_000)
        .setTargetBufferBytes(24 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()
    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setLoadControl(loadControl)
        .setTrackSelector(trackSelector)
        .setLivePlaybackSpeedControl(liveSpeedControl)
        .setSeekBackIncrementMs(30_000)
        .setSeekForwardIncrementMs(30_000)
        .build().apply {
            playWhenReady = true
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage("et")
                .setPreferredTextLanguage("et")
                .build()
        }
    private val mediaSession = MediaSession.Builder(appContext, player)
        .setId("${appContext.packageName}.main")
        .build()
    private val httpFactory = DefaultHttpDataSource.Factory().setUserAgent("Go3 Air/${BuildConfig.VERSION_NAME}")
    private var listener: Listener? = null
    private var baselineLiveOffsetMs: Long? = null
    private var currentChannelName = "Go3 Air"
    private var currentProgramTitle: String? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                if (playbackDiagnostics) Log.i("Go3Playback", "speed=${playbackParameters.speed} hardwareAvSyncRequested=$useHardwareAvSync")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackDiagnostics) Log.i("Go3Playback", "state=$playbackState bufferedMs=${player.totalBufferedDuration} hardwareAvSyncRequested=$useHardwareAvSync")
                if (playbackState == Player.STATE_READY) {
                    if (baselineLiveOffsetMs == null) {
                        baselineLiveOffsetMs = validTime(player.currentLiveOffset)
                    }
                    listener?.onReady()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.onError(error)
            }

            override fun onRenderedFirstFrame() {
                listener?.onFirstFrame()
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                if (playbackDiagnostics) Log.i("Go3Playback", "videoDecoder=$decoderName initMs=$initializationDurationMs hardwareAvSyncRequested=$useHardwareAvSync")
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                if (playbackDiagnostics) Log.i("Go3Playback", "video=${format.width}x${format.height} fps=${format.frameRate} bitrate=${format.bitrate}")
            }

            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                if (playbackDiagnostics) Log.w("Go3Playback", "droppedFrames=$droppedFrames elapsedMs=$elapsedMs bufferedMs=${player.totalBufferedDuration}")
            }
        })
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    /** Low-frequency, local diagnostics contain no stream URLs, account IDs or credentials. */
    fun reportPlaybackDiagnostics() {
        if (!playbackDiagnostics || !player.isPlaying) return
        val counters = player.videoDecoderCounters
        counters?.ensureUpdated()
        Log.i("Go3Playback", "health state=${player.playbackState} bufferedMs=${player.totalBufferedDuration} " +
            "speed=${player.playbackParameters.speed} fps=${player.videoFormat?.frameRate} " +
            "rendered=${counters?.renderedOutputBufferCount} dropped=${counters?.droppedBufferCount} " +
            "queued=${counters?.queuedInputBufferCount} heapMb=${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576} hardwareAvSyncRequested=$useHardwareAvSync")
    }

    fun play(
        ticket: PlaybackTicket,
        channelName: String = currentChannelName,
        programTitle: String? = currentProgramTitle,
    ) {
        baselineLiveOffsetMs = null
        currentChannelName = channelName
        currentProgramTitle = programTitle
        val mediaItem = MediaItem.Builder()
            .setUri(ticket.manifestUrl)
            .setMediaId(ticket.contentId)
            .setMimeType(ticket.mimeType)
            .setMediaMetadata(nowPlayingMetadata(channelName, programTitle))
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setMinPlaybackSpeed(minLiveSpeed)
                    .setMaxPlaybackSpeed(maxLiveSpeed)
                    .build(),
            )
            .apply {
                ticket.licenseUrl?.let { licenseUrl ->
                    setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                            .setLicenseUri(licenseUrl)
                            .setLicenseRequestHeaders(ticket.licenseRequestHeaders)
                            .build(),
                    )
                }
            }
            .build()
        val mediaSourceFactory = if (ticket.mimeType == MimeTypes.APPLICATION_MPD) {
            DashMediaSource.Factory(SubtitleTolerantDashChunkSourceFactory(httpFactory), httpFactory)
        } else {
            DefaultMediaSourceFactory(httpFactory)
        }
        player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
        player.prepare()
        player.playWhenReady = true
    }

    /** Refreshes what Android TV exposes to phones and other system media controls. */
    fun updateNowPlaying(channelName: String, programTitle: String?) {
        if (channelName == currentChannelName && programTitle == currentProgramTitle) return
        currentChannelName = channelName
        currentProgramTitle = programTitle
        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        if (index < 0) return
        player.replaceMediaItem(
            index,
            item.buildUpon().setMediaMetadata(nowPlayingMetadata(channelName, programTitle)).build(),
        )
    }

    fun setPlaying(playing: Boolean) {
        if (playing) player.play() else player.pause()
    }

    /** [audioLanguage] "auto" jätab valiku mängijale; [subtitleLanguage] null lülitab subtiitrid välja. */
    fun applyTrackPreferences(audioLanguage: String, subtitleLanguage: String?) {
        val builder = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setPreferredTextLanguage(subtitleLanguage)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitleLanguage == null)
        if (audioLanguage == "auto") builder.setPreferredAudioLanguages()
        else builder.setPreferredAudioLanguage(audioLanguage)
        player.trackSelectionParameters = builder.build()
    }

    fun seekSnapshot(): SeekSnapshot {
        val duration = validTime(player.duration) ?: 0L
        val rawPosition = player.currentPosition.coerceAtLeast(0L)
        val rawLiveOffset = validTime(player.currentLiveOffset)
        val baseline = baselineLiveOffsetMs
        val relativeLiveOffset = if (rawLiveOffset != null && baseline != null) {
            (rawLiveOffset - baseline).coerceAtLeast(0L)
        } else rawLiveOffset
        return SeekSnapshot(
            positionMs = if (duration > 0) rawPosition.coerceAtMost(duration) else rawPosition,
            durationMs = duration,
            // The service's normal DASH delay is the user's live edge, not "behind live".
            liveOffsetMs = relativeLiveOffset,
            isLive = player.isCurrentMediaItemLive,
            isPlaying = player.playWhenReady,
        )
    }

    fun seekBy(deltaMs: Long): SeekSnapshot {
        val before = seekSnapshot()
        if (before.durationMs <= 0L) return before
        val target = (before.positionMs + deltaMs).coerceIn(0L, before.durationMs)
        val reachesLiveEdge = before.isLive && deltaMs > 0L &&
            before.liveOffsetMs?.let { deltaMs >= it - 5_000L } == true
        if (reachesLiveEdge || (before.isLive && target >= before.durationMs - 15_000L)) {
            player.seekToDefaultPosition()
            val livePosition = (before.durationMs - (baselineLiveOffsetMs ?: 0L))
                .coerceIn(0L, before.durationMs)
            return before.copy(positionMs = livePosition, liveOffsetMs = 0L)
        } else {
            player.seekTo(target)
        }
        return seekSnapshot().copy(positionMs = target)
    }

    /** Re-anchor only after material live drift; manual time-shift disables this in the view model. */
    fun correctLiveDriftIfNeeded(): Boolean {
        if (!player.isCurrentMediaItemLive || !player.isPlaying) return false
        val currentOffset = validTime(player.currentLiveOffset) ?: return false
        val baseline = baselineLiveOffsetMs ?: currentOffset.also { baselineLiveOffsetMs = it }
        if (currentOffset <= baseline + 20_000L) return false
        player.seekToDefaultPosition()
        return true
    }

    /** Drop the decoder and its last frame so standby cannot restore stale video state. */
    fun stopAndClear() {
        baselineLiveOffsetMs = null
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
    }

    fun release() {
        mediaSession.release()
        player.release()
    }

    interface Listener {
        fun onReady()
        fun onFirstFrame()
        fun onError(error: PlaybackException)
    }
}

private fun validTime(value: Long): Long? = value.takeIf { it != C.TIME_UNSET && it >= 0L }

private const val MIN_SMOOTH_VIDEO_FRAME_RATE = 48

private fun nowPlayingMetadata(channelName: String, programTitle: String?): MediaMetadata =
    MediaMetadata.Builder()
        .setTitle(programTitle ?: channelName)
        .setArtist(if (programTitle == null) "Go3 Air" else channelName)
        .setSubtitle(channelName)
        .build()
