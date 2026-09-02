package ee.local.go3tvplus.data

import ee.local.go3tvplus.domain.StationObservation
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
    suspend fun observations(stationNames: Set<String>): Map<String, StationObservation> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(observationsUrl).header("Accept", "application/xml").build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Ilmateenistus vastas HTTP ${response.code}")
            response.body.string()
        }
        parse(body, stationNames)
    }

    internal fun parse(xml: String, stationNames: Set<String>): Map<String, StationObservation> {
        val document = runCatching {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrElse { throw IOException("Ilmateenistuse vastus ei olnud loetav", it) }
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
