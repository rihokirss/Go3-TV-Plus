package ee.local.go3tvplus.catalog

import kotlinx.serialization.json.*
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

enum class CatalogKind { MOVIE, SERIES, EPISODE }
enum class ViewingRight { AVAILABLE, LOCKED, UNKNOWN, EXPIRED }
enum class CatalogSort(val label: String) { ADDED("Hiljuti lisatud"), TITLE("Pealkiri"), YEAR("Väljalaskeaasta") }
data class CatalogTitle(
    val id: String, val kind: CatalogKind, val title: String, val originalTitle: String?,
    val description: String?, val poster: String?, val backdrop: String?, val year: Int?,
    val age: Int?, val durationSeconds: Long?, val genres: List<String>, val audio: List<String>,
    val subtitles: List<String>, val added: Long, val availableFrom: Long?, val availableUntil: Long?,
    val active: Boolean, val episode: Int? = null, val seriesId: String? = null, val seasonId: String? = null,
    val right: ViewingRight = ViewingRight.UNKNOWN,
) {
    val searchText get() = normalizeCatalogText("$title ${originalTitle.orEmpty()}")
    fun availableAt(now: Long = System.currentTimeMillis()) = active && (availableFrom == null || availableFrom <= now) && (availableUntil == null || availableUntil > now)
}
data class CatalogSeason(val id: String, val seriesId: String, val number: Int, val title: String)
data class CatalogPage(val items: List<JsonObject>, val total: Int, val consumed: Int)
data class CatalogRights(val ids: Map<String, Long?>, val checkedAt: Long = System.currentTimeMillis())
data class CatalogProgress(val profileId: String, val contentId: String, val seriesId: String?, val positionMs: Long, val durationMs: Long, val updatedAt: Long, val completed: Boolean = false, val continueEligible: Boolean = true) {
    val watched get() = completed || (durationMs > 0 && positionMs.toDouble() / durationMs >= .95)
    val fraction get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}
private val combiningMarks = Regex("\\p{M}+")
private val nonSearchCharacters = Regex("[^\\p{L}\\p{N}]+")
private val htmlTags = Regex("<[^>]*>")
private val imageWidth = Regex("([?&])dstw=[^&]+")
private val imageHeight = Regex("([?&])dsth=[^&]+")
fun normalizeCatalogText(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
    .replace(combiningMarks, "").lowercase(Locale.ROOT).replace(nonSearchCharacters, " ").trim()
fun catalogSearchQuery(text: String): String? = normalizeCatalogText(text).split(' ').filter(String::isNotBlank)
    .joinToString(" ") { "\"$it*\"" }.takeIf(String::isNotBlank)
internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.number(key: String): Long? = text(key)?.toLongOrNull()
internal fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.array(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())
private fun JsonObject.date(key: String): Long? = text(key)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
fun parseCatalogTitle(raw: JsonObject, right: ViewingRight = ViewingRight.UNKNOWN, seriesId: String? = null, seasonId: String? = null): CatalogTitle? {
    val kind = when (raw.text("type")) { "VOD" -> CatalogKind.MOVIE; "SERIAL" -> CatalogKind.SERIES; "EPISODE" -> CatalogKind.EPISODE; else -> return null }
    fun names(key: String) = raw.array(key).mapNotNull { (it as? JsonObject)?.text("name") }.distinct()
    fun image(shape: String, miniature: Boolean): String? {
        val image = ((raw["images"] as? JsonObject)?.get(shape) as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        return (image.text(if (miniature) "miniUrl" else "mainUrl") ?: image.text("mainUrl"))?.let { if (it.startsWith("//")) "https:$it" else it }?.takeIf { it.startsWith("https://") }
    }
    val since = raw.date("since")
    return CatalogTitle(raw.text("id") ?: return null, kind, raw.text("title") ?: return null,
        raw.text("originalTitle"), (raw.text("lead") ?: raw.text("description"))?.replace(htmlTags, " "),
        image("3x4", false) ?: image("2x3", false), image("16x9", false)?.replace(imageWidth, "$1dstw=1280")?.replace(imageHeight, "$1dsth=720"), raw.number("year")?.toInt(), raw.number("rating")?.toInt(),
        raw.number("duration"), names("genres"), names("audioLanguages"), names("subtitleLanguages"), since ?: 0L,
        since, raw.date("till"), raw.flag("active") != false, raw.number("episode")?.toInt(), seriesId, seasonId, right)
}
fun resolveViewingRight(item: CatalogTitle, ids: Map<String, Long?>?, now: Long = System.currentTimeMillis()): ViewingRight {
    if (!item.availableAt(now)) return ViewingRight.EXPIRED
    if (ids == null) return ViewingRight.UNKNOWN
    if (!ids.containsKey(item.id)) return ViewingRight.LOCKED
    return if (ids[item.id]?.let { it <= now } == true) ViewingRight.EXPIRED else ViewingRight.AVAILABLE
}

/** Retain display metadata, not trailers, provider logos, galleries or duplicate image variants. */
internal fun compactCatalogJson(raw: JsonObject): JsonObject = buildJsonObject {
    listOf("id", "type", "title", "originalTitle", "active", "since", "till", "year", "rating", "duration", "episode").forEach { key -> raw[key]?.let { put(key, it) } }
    (raw["lead"] ?: raw["description"])?.let { put("lead", it) }
    listOf("genres", "audioLanguages", "subtitleLanguages").forEach { key ->
        put(key, JsonArray(raw.array(key).mapNotNull { (it as? JsonObject)?.text("name") }.map { buildJsonObject { put("name", it) } }))
    }
    val images = raw["images"] as? JsonObject
    put("images", buildJsonObject {
        listOf("3x4", "2x3", "16x9").forEach { shape ->
            val image = (images?.get(shape) as? JsonArray)?.firstOrNull() as? JsonObject
            image?.let { put(shape, JsonArray(listOf(JsonObject(it.filterKeys { key -> key == "mainUrl" || key == "miniUrl" })))) }
        }
    })
}
