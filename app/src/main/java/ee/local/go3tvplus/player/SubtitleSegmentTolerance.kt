@file:androidx.media3.common.util.UnstableApi

package ee.local.go3tvplus.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.dash.BaseUrlExclusionList
import androidx.media3.exoplayer.dash.DashChunkSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.dash.PlayerEmsgHandler.PlayerTrackEmsgHandler
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.LoaderErrorThrower
import androidx.media3.extractor.text.SubtitleParser
import java.io.InterruptedIOException

/**
 * Go3 live streams regularly fail single subtitle segments (404, 503). Media3 reacts by disabling
 * the text track until the next track selection, and some errors also end playback, so subtitles
 * vanish or the channel re-tunes. Subtitle chunks therefore get a data source that, after the same
 * short retries Media3 would make, treats a failed segment as empty: only that segment's lines
 * are lost.
 */
class SubtitleTolerantDashChunkSourceFactory(dataSourceFactory: DataSource.Factory) : DashChunkSource.Factory {
    private val media = DefaultDashChunkSource.Factory(dataSourceFactory)
    private val text = DefaultDashChunkSource.Factory(FailedSegmentAsEmptyDataSource.Factory(dataSourceFactory))

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory) = apply {
        media.setSubtitleParserFactory(subtitleParserFactory)
        text.setSubtitleParserFactory(subtitleParserFactory)
    }

    override fun experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction: Boolean) = apply {
        media.experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction)
        text.experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction)
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(
        codecsToParseWithinGopSampleDependencies: Int,
    ) = apply {
        media.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
        text.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
    }

    override fun getOutputTextFormat(sourceFormat: Format): Format = text.getOutputTextFormat(sourceFormat)

    override fun createDashChunkSource(
        manifestLoaderErrorThrower: LoaderErrorThrower,
        manifest: DashManifest,
        baseUrlExclusionList: BaseUrlExclusionList,
        periodIndex: Int,
        adaptationSetIndices: IntArray,
        trackSelection: ExoTrackSelection,
        trackType: Int,
        elapsedRealtimeOffsetMs: Long,
        enableEventMessageTrack: Boolean,
        closedCaptionFormats: List<Format>,
        playerEmsgHandler: PlayerTrackEmsgHandler?,
        transferListener: TransferListener?,
        playerId: PlayerId,
        cmcdConfiguration: CmcdConfiguration?,
    ): DashChunkSource = (if (trackType == C.TRACK_TYPE_TEXT) text else media).createDashChunkSource(
        manifestLoaderErrorThrower,
        manifest,
        baseUrlExclusionList,
        periodIndex,
        adaptationSetIndices,
        trackSelection,
        trackType,
        elapsedRealtimeOffsetMs,
        enableEventMessageTrack,
        closedCaptionFormats,
        playerEmsgHandler,
        transferListener,
        playerId,
        cmcdConfiguration,
    )
}

private class FailedSegmentAsEmptyDataSource(private val upstream: DataSource) : DataSource {
    private var dataSpec: DataSpec? = null
    private var skipped = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        skipped = false
        var attempt = 0
        while (true) {
            try {
                return upstream.open(dataSpec)
            } catch (error: HttpDataSource.HttpDataSourceException) {
                upstream.close()
                if (error.cause is InterruptedIOException) throw error
                val retryDelayMs = RETRY_DELAYS_MS.getOrNull(attempt++)
                if (retryDelayMs == null) {
                    Log.w(TAG, "Skipping failed subtitle segment ${dataSpec.uri.lastPathSegment}: ${error.message}")
                    skipped = true
                    return 0
                }
                sleep(retryDelayMs)
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        if (skipped) C.RESULT_END_OF_INPUT else upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = if (skipped) dataSpec?.uri else upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (skipped) emptyMap() else upstream.responseHeaders

    override fun close() = upstream.close()

    private fun sleep(delayMs: Long) {
        try {
            Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException()
        }
    }

    class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = FailedSegmentAsEmptyDataSource(upstreamFactory.createDataSource())
    }
}

private const val TAG = "Go3Subtitles"

// Media3 retries a failed chunk after 0, 1 and 2 s; the first attempt is the open above.
private val RETRY_DELAYS_MS = listOf(0L, 1_000L, 2_000L)
