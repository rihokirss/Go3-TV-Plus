package ee.local.go3tvplus.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

data class WeatherLocation(
    val name: String,
    val area: String?,
    val latitude: Double,
    val longitude: Double,
)

data class CurrentWeather(
    val temperatureC: Double,
    val apparentTemperatureC: Double,
    val humidityPercent: Int,
    val precipitationMm: Double,
    val weatherCode: Int,
    val isDay: Boolean,
    val windSpeedMs: Double,
    val windDirectionDegrees: Int,
    val windGustMs: Double,
)

data class HourlyWeather(
    val time: LocalDateTime,
    val temperatureC: Double,
    val precipitationProbability: Int,
    val weatherCode: Int,
    val windSpeedMs: Double = 0.0,
    val windGustMs: Double = 0.0,
    val windDirectionDegrees: Int = 0,
    val visibilityKm: Double? = null,
)

data class DailyWeather(
    val date: LocalDate,
    val weatherCode: Int,
    val minimumTemperatureC: Double,
    val maximumTemperatureC: Double,
)

data class WeatherForecast(
    val location: WeatherLocation,
    val current: CurrentWeather,
    val hours: List<HourlyWeather>,
    val days: List<DailyWeather>,
    val fetchedAt: Instant,
    val sunrise: LocalDateTime? = null,
    val sunset: LocalDateTime? = null,
)

/** WMO ilmakoodide rühmad; ikoonid, animatsioonid ja muutuse tuvastus töötavad rühma, mitte koodi tasemel. */
enum class WeatherGroup { CLEAR, CLOUDY, FOG, RAIN, SNOW, THUNDER }

fun weatherGroup(code: Int): WeatherGroup = when {
    code <= 1 -> WeatherGroup.CLEAR
    code in 45..48 -> WeatherGroup.FOG
    code in 51..67 || code in 80..82 -> WeatherGroup.RAIN
    code in 71..77 || code in 85..86 -> WeatherGroup.SNOW
    code >= 95 -> WeatherGroup.THUNDER
    else -> WeatherGroup.CLOUDY
}

val DEFAULT_WEATHER_LOCATION = WeatherLocation(
    name = "Suurupi",
    area = "Harku vald, Harju maakond",
    latitude = 59.46255,
    longitude = 24.39193,
)
