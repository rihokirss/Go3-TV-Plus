package ee.local.go3tvplus.domain

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalDate

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
)

val DEFAULT_WEATHER_LOCATION = WeatherLocation(
    name = "Suurupi",
    area = "Harku vald, Harju maakond",
    latitude = 59.46255,
    longitude = 24.39193,
)
