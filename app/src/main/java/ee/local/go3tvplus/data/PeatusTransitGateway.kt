package ee.local.go3tvplus.data

import ee.local.go3tvplus.domain.TransitBoard
import ee.local.go3tvplus.domain.TransitDeparture
import ee.local.go3tvplus.domain.TransitStopPlatform
import ee.local.go3tvplus.domain.TransitStopSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class PeatusTransitGateway(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val endpoint: String = "https://api.peatus.ee/routing/v1/routers/estonia/index/graphql",
    private val clock: Clock = Clock.systemUTC(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun searchStops(query: String): List<TransitStopSelection> {
        if (query.trim().length < 2) return emptyList()
        val variables = buildJsonObject { put("name", query.trim()) }
        val stops = execute(STOP_SEARCH_QUERY, variables).obj("data")?.array("stops").orEmpty()
            .mapNotNull { element ->
                val stop = element as? JsonObject ?: return@mapNotNull null
                val id = stop.string("gtfsId") ?: return@mapNotNull null
                val name = stop.string("name") ?: return@mapNotNull null
                val latitude = stop.double("lat") ?: return@mapNotNull null
                val longitude = stop.double("lon") ?: return@mapNotNull null
                name to TransitStopPlatform(
                    id = id,
                    code = stop.string("code").orEmpty(),
                    latitude = latitude,
                    longitude = longitude,
                )
            }
        val groups = mutableListOf<Pair<String, MutableList<TransitStopPlatform>>>()
        stops.forEach { (name, platform) ->
            val group = groups.firstOrNull { (groupName, platforms) ->
                groupName.equals(name, ignoreCase = true) &&
                    platforms.firstOrNull()?.let { distanceMeters(it, platform) < STOP_GROUP_RADIUS_METERS } == true
            }
            if (group == null) groups += name to mutableListOf(platform) else group.second += platform
        }
        return groups.map { (name, platforms) ->
            TransitStopSelection(name, platforms.distinctBy(TransitStopPlatform::id).sortedBy(TransitStopPlatform::code))
        }.sortedWith(
            compareByDescending<TransitStopSelection> { it.name.equals(query.trim(), ignoreCase = true) }
                .thenBy(TransitStopSelection::name),
        ).take(MAX_STOP_RESULTS)
    }

    suspend fun departures(selection: TransitStopSelection): TransitBoard {
        val variables = buildJsonObject {
            put("ids", JsonArray(selection.platforms.map { JsonPrimitive(it.id) }))
        }
        val stops = execute(DEPARTURES_QUERY, variables).obj("data")?.array("stops").orEmpty()
        val now = Instant.now(clock)
        val departures = stops.flatMap { stopElement ->
            val stop = stopElement as? JsonObject ?: return@flatMap emptyList()
            val stopCode = stop.string("code").orEmpty()
            stop.array("stoptimesWithoutPatterns").mapNotNull { timeElement ->
                val time = timeElement as? JsonObject ?: return@mapNotNull null
                val serviceDay = time.long("serviceDay") ?: return@mapNotNull null
                val scheduledSeconds = time.long("scheduledDeparture") ?: return@mapNotNull null
                val realtime = time.boolean("realtime") == true
                val realtimeSeconds = time.long("realtimeDeparture")
                val trip = time.obj("trip")
                val route = trip?.obj("route")
                val patternStops = trip?.obj("pattern")?.array("stops").orEmpty()
                    .mapNotNull { (it as? JsonObject)?.string("name") }
                val departure = TransitDeparture(
                    stopCode = stopCode,
                    routeShortName = route?.string("shortName") ?: "–",
                    origin = patternStops.firstOrNull()?.cleanStopName() ?: "Algpeatus",
                    destination = time.string("headsign")?.cleanStopName()?.ifBlank { "Sihtkoht puudub" }
                        ?: "Sihtkoht puudub",
                    scheduledAt = Instant.ofEpochSecond(serviceDay + scheduledSeconds),
                    departureAt = Instant.ofEpochSecond(
                        serviceDay + if (realtime && realtimeSeconds != null) realtimeSeconds else scheduledSeconds,
                    ),
                    realtime = realtime,
                    cancelled = time.string("realtimeState") == "CANCELED",
                )
                departure.takeIf { it.departureAt >= now.minusSeconds(60) }
            }
        }.sortedBy(TransitDeparture::departureAt).take(MAX_DEPARTURES)
        return TransitBoard(
            stopName = stops.firstOrNull()?.let { it as? JsonObject }?.string("name") ?: selection.name,
            departures = departures,
            fetchedAt = now,
        )
    }

    private suspend fun execute(query: String, variables: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val requestBody = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(endpoint).header("Accept", "application/json").post(requestBody).build()
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw IOException("Peatus.ee vastas HTTP ${response.code}")
            val root = runCatching { json.parseToJsonElement(body).jsonObject }
                .getOrElse { throw IOException("Peatus.ee vastus ei olnud loetav", it) }
            val apiError = root.array("errors").firstOrNull()?.let { it as? JsonObject }?.string("message")
            if (apiError != null) throw IOException(apiError)
            root
        }
    }

    private companion object {
        const val MAX_DEPARTURES = 60
        const val MAX_STOP_RESULTS = 8
        const val STOP_GROUP_RADIUS_METERS = 1_000.0
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val STOP_SEARCH_QUERY = """
            query StopSearch(${ '$' }name: String!) {
              stops(name: ${ '$' }name, maxResults: 30) { gtfsId name code lat lon }
            }
        """.trimIndent()
        val DEPARTURES_QUERY = """
            query Departures(${ '$' }ids: [String]) {
              stops(ids: ${ '$' }ids) {
                name
                code
                stoptimesWithoutPatterns(numberOfDepartures: 30, omitNonPickups: true) {
                  scheduledDeparture
                  realtimeDeparture
                  realtime
                  realtimeState
                  serviceDay
                  headsign
                  trip {
                    route { shortName }
                    pattern { stops { name } }
                  }
                }
              }
            }
        """.trimIndent()
    }
}

private fun distanceMeters(first: TransitStopPlatform, second: TransitStopPlatform): Double {
    val earthRadius = 6_371_000.0
    val latitudeDelta = Math.toRadians(second.latitude - first.latitude)
    val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
    val firstLatitude = Math.toRadians(first.latitude)
    val secondLatitude = Math.toRadians(second.latitude)
    val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
        cos(firstLatitude) * cos(secondLatitude) * sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
    return earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))
}

private fun String.cleanStopName() = replace(" (train station)", "")

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
private fun JsonObject.array(name: String): JsonArray = this[name] as? JsonArray ?: JsonArray(emptyList())
private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
private fun JsonObject.double(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
