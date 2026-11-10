package ee.local.go3tvplus.data

import ee.local.go3tvplus.domain.CatalogMovie
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/** SERIAL and CLIP entries must not masquerade as a specific episode or full movie. */
internal fun JsonObject.toCatalogMovie(): CatalogMovie? {
    fun text(key: String) = get(key)?.jsonPrimitive?.contentOrNull
    fun instant(key: String) = text(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }
    if (text("type") != "VOD" || get("active")?.jsonPrimitive?.booleanOrNull != true) return null
    val duration = get("duration")?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return null
    return CatalogMovie(
        id = text("id") ?: return null,
        externalId = text("externalUid")?.takeIf(String::isNotBlank) ?: return null,
        title = text("title") ?: return null,
        originalTitle = text("originalTitle"),
        durationSeconds = duration,
        availableFrom = instant("since") ?: return null,
        availableUntil = instant("till"),
    )
}
