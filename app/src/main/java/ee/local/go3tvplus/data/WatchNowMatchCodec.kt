package ee.local.go3tvplus.data

import ee.local.go3tvplus.data.local.WatchNowMatchEntity
import ee.local.go3tvplus.domain.CatalogMovie
import ee.local.go3tvplus.domain.Program
import ee.local.go3tvplus.domain.WatchNowResolver
import ee.local.go3tvplus.domain.WatchNowSource
import kotlinx.serialization.json.*
import java.time.Instant

/** Saves content identities and source validity, never playback URLs or credentials. */
internal object WatchNowMatchCodec {
    fun requestKey(program: Program): String = JsonArray(listOf(
        program.id, program.channelId, program.title, program.originalTitle,
        program.secondaryId, program.epgProvider, program.startsAt.toString(), program.endsAt.toString(),
    ).map { it?.let(::JsonPrimitive) ?: JsonNull }).toString()

    fun encode(profile: String, program: Program, sources: List<WatchNowSource>, at: Instant): WatchNowMatchEntity =
        WatchNowMatchEntity(profile, program.id, requestKey(program), JsonArray(sources.map { source ->
            buildJsonObject {
                when (source) {
                    is WatchNowSource.Movie -> with(source.movie) {
                        put("kind", "movie"); put("id", id); put("externalId", externalId); put("title", title)
                        originalTitle?.let { put("originalTitle", it) }
                        put("duration", durationSeconds); put("from", availableFrom.toString())
                        availableUntil?.let { put("until", it.toString()) }
                    }
                    is WatchNowSource.Recording -> with(source.program) {
                        put("kind", "recording"); put("id", id); put("channelId", channelId); put("title", title)
                        put("start", startsAt.toString()); put("end", endsAt.toString())
                        put("catchup", catchupAvailable)
                        secondaryId?.let { put("secondaryId", it) }; epgProvider?.let { put("provider", it) }
                        catchupUntil?.let { put("until", it.toString()) }
                    }
                }
            }
        }).toString(), at.toEpochMilli(), program.startsAt.toEpochMilli())

    fun decode(row: WatchNowMatchEntity, program: Program, channels: Set<String>, now: Instant): List<WatchNowSource> {
        if (row.requestKey != requestKey(program) || row.expiresAtEpochMs <= now.toEpochMilli()) return emptyList()
        val values = runCatching { Json.parseToJsonElement(row.sourcesJson).jsonArray }.getOrNull() ?: return emptyList()
        return values.flatMap { value ->
            runCatching {
                val obj = value.jsonObject
                fun text(key: String): String = obj.getValue(key).jsonPrimitive.content
                fun optional(key: String): String? = obj[key]?.jsonPrimitive?.contentOrNull
                fun instant(key: String) = Instant.parse(text(key))
                when (text("kind")) {
                    "movie" -> WatchNowResolver.movies(program, listOf(CatalogMovie(
                        text("id"), text("externalId"), text("title"), optional("originalTitle"),
                        text("duration").toLong(), instant("from"), optional("until")?.let(Instant::parse),
                    )), now)
                    "recording" -> WatchNowResolver.recordings(program, listOf(Program(
                        text("id"), text("channelId"), text("title"), startsAt = instant("start"), endsAt = instant("end"),
                        catchupAvailable = text("catchup").toBooleanStrict(), secondaryId = optional("secondaryId"),
                        epgProvider = optional("provider"), catchupUntil = optional("until")?.let(Instant::parse),
                    )), channels, now)
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())
        }
    }
}
