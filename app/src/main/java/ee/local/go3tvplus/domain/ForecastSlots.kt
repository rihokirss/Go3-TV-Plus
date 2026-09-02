package ee.local.go3tvplus.domain

import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * Valib tunniprognoosist ekraanile mahtuvad tulbad: vaikimisi iga [baseStepHours] tunni tagant,
 * aga kui vahepealne tund erineb viimati näidatust märgatavalt, näidatakse ka seda.
 * Nii on rahulik ilm hõre ja ilmamuutus tihe.
 */
object ForecastSlots {
    fun <T> select(
        items: List<T>,
        maxCount: Int,
        baseStepHours: Long,
        timeOf: (T) -> LocalDateTime,
        notableChange: (T, T) -> Boolean,
    ): List<T> {
        if (items.isEmpty()) return emptyList()
        val selected = mutableListOf<T>(items.first())
        var last: T = items.first()
        for (item in items.drop(1)) {
            if (selected.size >= maxCount) break
            val gapHours = Duration.between(timeOf(last), timeOf(item)).toHours()
            if (gapHours >= baseStepHours || notableChange(last, item)) {
                selected += item
                last = item
            }
        }
        return selected
    }

    fun notableWeatherChange(previous: HourlyWeather, next: HourlyWeather): Boolean =
        severity(previous.weatherCode) != severity(next.weatherCode) ||
            abs(previous.precipitationProbability - next.precipitationProbability) >= 30 ||
            abs(previous.temperatureC - next.temperatureC) >= 3.0 ||
            abs(previous.windSpeedMs - next.windSpeedMs) >= 4.0 ||
            abs(previous.windGustMs - next.windGustMs) >= 5.0

    fun notableSeaChange(previous: SeaHour, next: SeaHour): Boolean =
        SeaConditions.rate(previous) != SeaConditions.rate(next) ||
            severity(previous.weatherCode) != severity(next.weatherCode) ||
            abs(previous.windSpeedMs - next.windSpeedMs) >= 3.0 ||
            abs((previous.waveHeightM ?: 0.0) - (next.waveHeightM ?: 0.0)) >= 0.3

    /** Selge ja pilves on sama "kuiv" klass; muutuseks loeb udu, sadu või äike. */
    private fun severity(code: Int): Int = when (weatherGroup(code)) {
        WeatherGroup.CLEAR, WeatherGroup.CLOUDY -> 0
        WeatherGroup.FOG -> 1
        WeatherGroup.RAIN -> 2
        WeatherGroup.SNOW -> 3
        WeatherGroup.THUNDER -> 4
    }
}
