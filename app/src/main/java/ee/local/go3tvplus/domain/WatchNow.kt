package ee.local.go3tvplus.domain

import java.time.Instant
import java.time.Duration
import java.util.Locale

data class CatalogMovie(
    val id: String,
    val externalId: String,
    val title: String,
    val originalTitle: String?,
    val durationSeconds: Long,
    val availableFrom: Instant,
    val availableUntil: Instant?,
)

sealed interface WatchNowSource {
    data class Recording(val program: Program) : WatchNowSource
    data class Movie(val movie: CatalogMovie) : WatchNowSource
}

/** EPG identity identifies the episode; a shared series title is never sufficient. */
object WatchNowResolver {
    val CACHE_TTL: Duration = Duration.ofMinutes(5)
    val EMPTY_CACHE_TTL: Duration = Duration.ofMinutes(1)

    /** Schedule descriptions and future catchup flags change without changing the content. */
    fun sameContent(left: Program?, right: Program?): Boolean = left != null && right != null &&
        left.id == right.id && left.channelId == right.channelId &&
        left.startsAt == right.startsAt && left.endsAt == right.endsAt &&
        left.secondaryId == right.secondaryId && left.epgProvider == right.epgProvider &&
        left.title == right.title && left.originalTitle == right.originalTitle

    fun recordings(
        requested: Program,
        candidates: List<Program>,
        allowedChannelIds: Set<String>,
        now: Instant,
    ): List<WatchNowSource.Recording> {
        val identity = requested.secondaryId?.takeIf(::validIdentity) ?: return emptyList()
        val provider = requested.epgProvider?.takeIf(String::isNotBlank) ?: return emptyList()
        return candidates.asSequence().filter {
            it.id != requested.id && it.secondaryId == identity && it.epgProvider == provider &&
                sameTitle(requested.title, it.title) &&
                it.channelId in allowedChannelIds && !it.endsAt.isAfter(now) &&
                it.catchupAvailable && it.catchupUntil?.isAfter(now) == true
        }.distinctBy(Program::id)
            .sortedByDescending(Program::startsAt)
            .map(WatchNowSource::Recording).toList()
    }

    fun movies(requested: Program, candidates: List<CatalogMovie>, now: Instant): List<WatchNowSource.Movie> {
        val identity = requested.secondaryId?.takeIf(::validIdentity) ?: return emptyList()
        // This shared identity namespace is verified for the T24 EPG feed only.
        if (requested.epgProvider != "T24") return emptyList()
        return candidates.filter {
            it.externalId == identity && it.durationSeconds > 0 && !it.availableFrom.isAfter(now) &&
                (it.availableUntil == null || it.availableUntil.isAfter(now)) &&
                (sameTitle(requested.title, it.title) || sameTitle(requested.originalTitle, it.originalTitle))
        }.distinctBy(CatalogMovie::id).map(WatchNowSource::Movie)
    }

    fun validIdentity(value: String): Boolean = value.isNotBlank() && value != "0" && value != "null"

    private fun sameTitle(left: String?, right: String?): Boolean {
        fun normalize(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        if (left.isNullOrBlank() || right.isNullOrBlank()) return false
        return normalize(left).let { it.isNotEmpty() && it == normalize(right) }
    }
}
