package ee.local.go3tvplus.ui

import ee.local.go3tvplus.domain.Channel
import ee.local.go3tvplus.domain.Program
import ee.local.go3tvplus.domain.number
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Puhas, testitav loogika, mida [TvViewModel] kasutab. */

internal object SettingsNavigation {
    fun parent(overlay: Overlay, returnOverlay: Overlay): Overlay = when (overlay) {
        Overlay.LOCATIONS_SETTINGS -> Overlay.APP_SETTINGS
        Overlay.SEA_SETTINGS -> Overlay.LOCATIONS_SETTINGS
        Overlay.SEA_STATION_PICKER -> Overlay.SEA_SETTINGS
        else -> if (overlay.returnsToParent) returnOverlay else Overlay.NONE
    }
}

internal fun <T> cycleOption(options: List<T>, active: T, direction: Int): T =
    options[Math.floorMod(options.indexOf(active).coerceAtLeast(0) + direction, options.size)]

object ChannelNumberResolver {
    fun resolve(channels: List<Channel>, number: Int?): Channel? {
        if (number == null || number !in 1..999) return null
        return channels.firstOrNull { it.number == number }
    }

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

internal object TonightScheduleResolver {
    private val WINDOW_LENGTH: Duration = Duration.ofHours(5)

    /**
     * Õhtuse akna algus: päevasel avamisel tänane 19:00, õhtul või öösel
     * avamisel praegune hetk (siis näitab paneel käimasolevat ja järgnevat).
     */
    fun windowStart(now: Instant, zone: ZoneId): Instant {
        val local = now.atZone(zone)
        return if (local.hour in 4..18) {
            local.toLocalDate().atTime(19, 0).atZone(zone).toInstant()
        } else {
            now
        }
    }

    fun entries(
        channels: List<Channel>,
        favoriteChannelIds: Set<String>,
        programsByChannel: Map<String, List<Program>>,
        now: Instant,
        zone: ZoneId,
    ): List<TonightEntry> {
        val start = windowStart(now, zone)
        val end = start.plus(WINDOW_LENGTH)
        val base = channels.filter { it.id in favoriteChannelIds }.ifEmpty { channels }
        return base.flatMap { channel ->
            programsByChannel[channel.id].orEmpty()
                .filter { it.endsAt.isAfter(start) && it.startsAt.isBefore(end) }
                .map { TonightEntry(channel, it) }
        }.sortedWith(compareBy({ it.program.startsAt }, { it.channel.number }))
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
