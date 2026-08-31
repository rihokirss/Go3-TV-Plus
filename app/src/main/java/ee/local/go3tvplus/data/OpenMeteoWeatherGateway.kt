package ee.local.go3tvplus.data

import ee.local.go3tvplus.domain.CurrentWeather
import ee.local.go3tvplus.domain.HourlyWeather
import ee.local.go3tvplus.domain.DailyWeather
import ee.local.go3tvplus.domain.WeatherForecast
import ee.local.go3tvplus.domain.WeatherLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class OpenMeteoWeatherGateway(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val geocodingBaseUrl: String = "https://geocoding-api.open-meteo.com/v1/search",
    private val forecastBaseUrl: String = "https://api.open-meteo.com/v1/forecast",
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun searchLocations(query: String): List<WeatherLocation> {
        if (query.trim().length < 2) return emptyList()
        val url = geocodingBaseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("name", query.trim())
            .addQueryParameter("count", "8")
            .addQueryParameter("language", "et")
            .addQueryParameter("format", "json")
            .addQueryParameter("countryCode", "EE")
            .build()
        val root = execute(url).jsonObject
        return root.array("results").mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val name = item.string("name") ?: return@mapNotNull null
            val latitude = item.double("latitude") ?: return@mapNotNull null
            val longitude = item.double("longitude") ?: return@mapNotNull null
            WeatherLocation(
                name = name,
                area = listOfNotNull(item.string("admin2"), item.string("admin1"))
                    .distinct().joinToString(", ").ifBlank { null },
                latitude = latitude,
                longitude = longitude,
            )
        }.distinctBy { "${it.name}|${it.latitude}|${it.longitude}" }
    }

    suspend fun forecast(location: WeatherLocation): WeatherForecast {
        val currentVariables = listOf(
            "temperature_2m", "apparent_temperature", "relative_humidity_2m", "precipitation",
            "weather_code", "is_day", "wind_speed_10m", "wind_direction_10m", "wind_gusts_10m",
        ).joinToString(",")
        val hourlyVariables = listOf(
            "temperature_2m", "precipitation_probability", "weather_code",
        ).joinToString(",")
        val dailyVariables = listOf(
            "weather_code", "temperature_2m_max", "temperature_2m_min",
        ).joinToString(",")
        val url = forecastBaseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("latitude", location.latitude.toString())
            .addQueryParameter("longitude", location.longitude.toString())
            .addQueryParameter("current", currentVariables)
            .addQueryParameter("hourly", hourlyVariables)
            .addQueryParameter("daily", dailyVariables)
            .addQueryParameter("forecast_hours", "8")
            .addQueryParameter("forecast_days", "5")
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("wind_speed_unit", "ms")
            .build()
        val root = execute(url).jsonObject
        val current = root.obj("current") ?: throw IOException("Ilma hetkeandmed puuduvad")
        val hourly = root.obj("hourly") ?: throw IOException("Ilma tunniprognoos puudub")
        val daily = root.obj("daily") ?: throw IOException("Ilma päevaprognoos puudub")
        val times = hourly.stringArray("time")
        val temperatures = hourly.doubleArray("temperature_2m")
        val precipitation = hourly.intArray("precipitation_probability")
        val codes = hourly.intArray("weather_code")
        val hours = times.indices.mapNotNull { index ->
            HourlyWeather(
                time = runCatching { LocalDateTime.parse(times[index]) }.getOrNull() ?: return@mapNotNull null,
                temperatureC = temperatures.getOrNull(index) ?: return@mapNotNull null,
                precipitationProbability = precipitation.getOrNull(index) ?: 0,
                weatherCode = codes.getOrNull(index) ?: 0,
            )
        }
        val dates = daily.stringArray("time")
        val dailyCodes = daily.intArray("weather_code")
        val minimums = daily.doubleArray("temperature_2m_min")
        val maximums = daily.doubleArray("temperature_2m_max")
        val days = dates.indices.mapNotNull { index ->
            DailyWeather(
                date = runCatching { LocalDate.parse(dates[index]) }.getOrNull() ?: return@mapNotNull null,
                weatherCode = dailyCodes.getOrNull(index) ?: 0,
                minimumTemperatureC = minimums.getOrNull(index) ?: return@mapNotNull null,
                maximumTemperatureC = maximums.getOrNull(index) ?: return@mapNotNull null,
            )
        }
        return WeatherForecast(
            location = location,
            current = CurrentWeather(
                temperatureC = current.double("temperature_2m") ?: 0.0,
                apparentTemperatureC = current.double("apparent_temperature") ?: 0.0,
                humidityPercent = current.int("relative_humidity_2m") ?: 0,
                precipitationMm = current.double("precipitation") ?: 0.0,
                weatherCode = current.int("weather_code") ?: 0,
                isDay = current.int("is_day") != 0,
                windSpeedMs = current.double("wind_speed_10m") ?: 0.0,
                windDirectionDegrees = current.int("wind_direction_10m") ?: 0,
                windGustMs = current.double("wind_gusts_10m") ?: 0.0,
            ),
            hours = hours,
            days = days,
            fetchedAt = Instant.now(),
        )
    }

    private suspend fun execute(url: HttpUrl) = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw IOException("Ilmateenus vastas HTTP ${response.code}")
            runCatching { json.parseToJsonElement(body) }
                .getOrElse { throw IOException("Ilmateenuse vastus ei olnud loetav", it) }
        }
    }
}

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
private fun JsonObject.array(name: String): JsonArray = this[name] as? JsonArray ?: JsonArray(emptyList())
private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.double(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
private fun JsonObject.stringArray(name: String) = array(name).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
private fun JsonObject.doubleArray(name: String) = array(name).mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
private fun JsonObject.intArray(name: String) = array(name).mapNotNull { (it as? JsonPrimitive)?.intOrNull }
