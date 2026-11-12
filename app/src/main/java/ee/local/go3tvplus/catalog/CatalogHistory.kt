package ee.local.go3tvplus.catalog

import kotlinx.serialization.json.*
import java.time.Instant

data class CatalogHistoryRecord(
    val item: JsonObject, val series: JsonObject?, val seriesId: String?, val seasonId: String?,
    val positionMs: Long, val durationMs: Long, val updatedAt: Long, val completed: Boolean, val continueEligible: Boolean,
) {
    val id get() = item.text("id")!!
}
data class CatalogHistoryStatus(val syncing: Boolean = false, val pending: Int = 0, val error: String? = null)

/** Go3's WATCHED bookmarks contain product metadata and a server modification time. */
fun parseCatalogHistory(root: JsonObject): List<CatalogHistoryRecord> {
    require(root["items"] is JsonArray) { "Go3 vaatamisjärje vastus ei ole sobiv" }
    val continueIds = root.array("continueWatchingItems").mapNotNull { ((it as? JsonObject)?.get("item") as? JsonObject)?.text("id") }.toSet()
    val supplements = root.array("continueWatchingItems").mapNotNull { (it as? JsonObject)?.get("item") as? JsonObject }.associateBy { it.text("id") }
    return root.array("items").mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val original = row["item"] as? JsonObject ?: return@mapNotNull null
        val item = JsonObject(original + supplements[original.text("id")].orEmpty())
        if (item.text("id") == null || item.text("type") !in listOf("VOD", "EPISODE")) return@mapNotNull null
        val status = item["status"] as? JsonObject
        val position = (status?.text("bookmark") ?: row.text("playTime"))?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
        val season = item["season"] as? JsonObject
        val series = (season?.get("serial") ?: season?.get("series") ?: item["serial"]) as? JsonObject
        val seriesId = series?.text("id") ?: season?.text("serialId") ?: item.text("serialId")
        val duration = (item.number("duration") ?: 0L).coerceAtLeast(0) * 1000
        val updated = listOf("modifiedAt", "createdAt").firstNotNullOfOrNull { key -> row.text(key)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } } ?: 0
        CatalogHistoryRecord(item, series, seriesId, season?.text("id"),
            (position * 1000).toLong().let { if (duration > 0) it.coerceAtMost(duration) else it }, duration, updated,
            row.text("watchedAt") != null, if (root["continueWatchingItems"] is JsonArray) item.text("id") in continueIds else row.text("watchedAt") == null)
    }.groupBy { it.id }.map { (_, values) -> values.maxBy { it.updatedAt } }
}

/** An intentional rewind is a new change too; never merge by the furthest position. */
fun remoteHistoryWins(remoteModifiedAt: Long, localModifiedAt: Long?, hasPendingLocal: Boolean): Boolean =
    localModifiedAt == null || (remoteModifiedAt > localModifiedAt + if (hasPendingLocal) 5000 else 0)
