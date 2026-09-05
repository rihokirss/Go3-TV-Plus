package ee.local.go3tvplus.data

import ee.local.go3tvplus.domain.StationObservation
import ee.local.go3tvplus.domain.SeaPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** Keskkonnaagentuuri ilmajaamade viimased mõõtmised (avalik XML, uueneb umbes iga 10 minuti tagant). */
class IlmateenistusGateway(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val observationsUrl: String = "https://www.ilmateenistus.ee/ilma_andmed/xml/observations.php",
) {
    suspend fun observations(stationNames: Set<String>): Map<String, StationObservation> =
        withContext(Dispatchers.IO) { parse(fetchXml(), stationNames) }

    suspend fun stations(): List<SeaPoint> =
        withContext(Dispatchers.IO) { parseStations(fetchXml()) }

    private fun fetchXml(): String {
        val request = Request.Builder().url(observationsUrl).header("Accept", "application/xml").build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Ilmateenistus vastas HTTP ${response.code}")
            response.body.string()
        }
        return body
    }

    /** Use the service's station names verbatim so observation lookup stays exact. */
    internal fun parseStations(xml: String): List<SeaPoint> {
        val nodes = document(xml).documentElement.getElementsByTagName("station")
        return (0 until nodes.length).mapNotNull { index ->
            val station = nodes.item(index) as? Element ?: return@mapNotNull null
            val name = station.text("name") ?: return@mapNotNull null
            // Groundwater wells are also in this feed, but are not useful marine observation points.
            if (station.text("windspeed")?.toDoubleOrNull() == null &&
                station.text("watertemperature")?.toDoubleOrNull() == null
            ) return@mapNotNull null
            val latitude = station.text("latitude")?.toDoubleOrNull() ?: return@mapNotNull null
            val longitude = station.text("longitude")?.toDoubleOrNull() ?: return@mapNotNull null
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return@mapNotNull null
            SeaPoint(name, name, latitude, longitude)
        }.distinctBy(SeaPoint::stationName).sortedBy(SeaPoint::name)
    }

    private fun document(xml: String) = runCatching {
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }.getOrElse { throw IOException("Ilmateenistuse vastus ei olnud loetav", it) }

    internal fun parse(xml: String, stationNames: Set<String>): Map<String, StationObservation> {
        val document = document(xml)
        val root = document.documentElement
        val observedAt = root.getAttribute("timestamp").toLongOrNull()?.let(Instant::ofEpochSecond) ?: Instant.now()
        val stations = root.getElementsByTagName("station")
        val result = LinkedHashMap<String, StationObservation>()
        for (index in 0 until stations.length) {
            val station = stations.item(index) as? Element ?: continue
            val name = station.text("name") ?: continue
            if (name !in stationNames) continue
            result[name] = StationObservation(
                stationName = name,
                observedAt = observedAt,
                airTemperatureC = station.text("airtemperature")?.toDoubleOrNull(),
                windDirectionDegrees = station.text("winddirection")?.toDoubleOrNull()?.toInt(),
                windSpeedMs = station.text("windspeed")?.toDoubleOrNull(),
                windGustMs = station.text("windspeedmax")?.toDoubleOrNull(),
                waterTemperatureC = station.text("watertemperature")?.toDoubleOrNull(),
                waterLevelCm = (station.text("waterlevel") ?: station.text("waterlevel_eh2000"))?.toDoubleOrNull()?.toInt(),
                visibilityKm = station.text("visibility")?.toDoubleOrNull(),
            )
        }
        return result
    }

    private fun Element.text(tag: String): String? =
        getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}
