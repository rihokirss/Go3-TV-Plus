package ee.local.go3tvplus.domain

import java.time.Instant
import java.time.LocalDateTime

/** Sadam või sihtpunkt koos Ilmateenistuse jaama nimega, mille reaalajaandmeid näidatakse. */
data class SeaPoint(
    val name: String,
    val stationName: String,
    val latitude: Double,
    val longitude: Double,
)

/** Kaatrisõidu marsruut: kodusadam, sihtkoht ja avamere punkt, mille järgi laine- ja tuuleprognoos käib. */
data class SeaRoute(
    val harbour: SeaPoint,
    val destination: SeaPoint,
    val seaLatitude: Double,
    val seaLongitude: Double,
) {
    val title: String get() = "${harbour.name} → ${destination.name}"
    val stationNames: Set<String> get() = setOf(harbour.stationName, destination.stationName)
}

val DEFAULT_SEA_ROUTE = SeaRoute(
    harbour = SeaPoint("Tilgu sadam", "Tilgu", 59.4558, 24.4881),
    destination = SeaPoint("Naissaar", "Naissaare", 59.5408, 24.5633),
    seaLatitude = 59.515,
    seaLongitude = 24.50,
)

enum class SeaForecastPosition(val label: String) {
    BETWEEN("Punktide vahel"), FIRST("Esimese punkti juures"), SECOND("Teise punkti juures");

    fun cycle(direction: Int) = entries[Math.floorMod(ordinal + direction, entries.size)]
}

/** Observation stations and model forecast location are separate choices. */
data class SeaLocationPreferences(
    val first: SeaPoint = DEFAULT_SEA_ROUTE.harbour,
    val second: SeaPoint = DEFAULT_SEA_ROUTE.destination,
    val forecastPosition: SeaForecastPosition = SeaForecastPosition.BETWEEN,
) {
    fun route(): SeaRoute {
        val point = when (forecastPosition) {
            SeaForecastPosition.FIRST -> first.latitude to first.longitude
            SeaForecastPosition.SECOND -> second.latitude to second.longitude
            SeaForecastPosition.BETWEEN ->
                (first.latitude + second.latitude) / 2 to (first.longitude + second.longitude) / 2
        }
        return SeaRoute(first, second, point.first, point.second)
    }
}

/** Ilmateenistuse automaatjaama viimane mõõtmine; puuduvad väljad on null. */
data class StationObservation(
    val stationName: String,
    val observedAt: Instant,
    val airTemperatureC: Double? = null,
    val windDirectionDegrees: Int? = null,
    val windSpeedMs: Double? = null,
    val windGustMs: Double? = null,
    val waterTemperatureC: Double? = null,
    val waterLevelCm: Int? = null,
    val visibilityKm: Double? = null,
)

data class SeaHour(
    val time: LocalDateTime,
    val windSpeedMs: Double,
    val windGustMs: Double,
    val windDirectionDegrees: Int,
    val waveHeightM: Double?,
    val wavePeriodS: Double?,
    val waveDirectionDegrees: Int?,
    val visibilityKm: Double?,
    val weatherCode: Int,
    val precipitationProbability: Int,
    val temperatureC: Double,
)

data class SeaForecast(
    val route: SeaRoute,
    val hours: List<SeaHour>,
    val seaSurfaceTemperatureC: Double?,
    val sunrise: LocalDateTime?,
    val sunset: LocalDateTime?,
    val fetchedAt: Instant,
    val harbourObservation: StationObservation? = null,
    val destinationObservation: StationObservation? = null,
)
