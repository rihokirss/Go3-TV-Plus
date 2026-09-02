package ee.local.go3tvplus.domain

import java.time.format.DateTimeFormatter
import java.util.Locale

/** Väikese kaatri jaoks: kas avamere lõik sadamast sihtkohta on hetkel mõistlik. */
enum class SeaCondition(val label: String) {
    CALM("SOBIB"),
    MODERATE("ETTEVAATLIKULT"),
    ROUGH("EI SOBI"),
}

object SeaConditions {
    fun rate(hour: SeaHour): SeaCondition {
        val wave = hour.waveHeightM ?: 0.0
        val visibility = hour.visibilityKm ?: Double.MAX_VALUE
        val group = weatherGroup(hour.weatherCode)
        return when {
            group == WeatherGroup.THUNDER -> SeaCondition.ROUGH
            hour.windSpeedMs > ROUGH_WIND_MS || hour.windGustMs > ROUGH_GUST_MS -> SeaCondition.ROUGH
            wave > ROUGH_WAVE_M || visibility < ROUGH_VISIBILITY_KM -> SeaCondition.ROUGH
            hour.windSpeedMs > MODERATE_WIND_MS || hour.windGustMs > MODERATE_GUST_MS -> SeaCondition.MODERATE
            wave > MODERATE_WAVE_M || visibility < MODERATE_VISIBILITY_KM -> SeaCondition.MODERATE
            hour.weatherCode in HEAVY_PRECIPITATION_CODES -> SeaCondition.MODERATE
            else -> SeaCondition.CALM
        }
    }

    /** Lühike eestikeelne hinnang: praegune olukord ja esimene muutus prognoosi ulatuses. */
    fun summary(hours: List<SeaHour>): String {
        val now = hours.firstOrNull() ?: return "Mereprognoos puudub"
        val nowRating = rate(now)
        val opening = when (nowRating) {
            SeaCondition.CALM -> "Praegu sobib sõiduks"
            SeaCondition.MODERATE -> "Praegu sõida ettevaatlikult"
            SeaCondition.ROUGH -> "Praegu merele ei sobi"
        }
        val change = hours.drop(1).firstOrNull { rate(it) != nowRating }
        val changeText = if (change == null) {
            "Olukord püsib kogu prognoosi ulatuses."
        } else {
            val verb = if (rate(change).ordinal > nowRating.ordinal) "Halveneb" else "Paraneb"
            "$verb kell ${change.time.format(HOUR_FORMAT)}: ${describe(change)}."
        }
        return "$opening: ${describe(now)}. $changeText"
    }

    fun describe(hour: SeaHour): String = buildString {
        append("tuul ${oneDecimal(hour.windSpeedMs)} m/s")
        if (hour.windGustMs >= hour.windSpeedMs + 2) append(" (puhangud ${oneDecimal(hour.windGustMs)})")
        hour.waveHeightM?.let { append(", laine ${oneDecimal(it)} m") }
        hour.visibilityKm?.takeIf { it < MODERATE_VISIBILITY_KM }?.let { append(", nähtavus ${oneDecimal(it)} km") }
        when (weatherGroup(hour.weatherCode)) {
            WeatherGroup.THUNDER -> append(", äike")
            WeatherGroup.FOG -> append(", udu")
            WeatherGroup.RAIN -> if (hour.weatherCode in HEAVY_PRECIPITATION_CODES) append(", tugev vihm")
            else -> Unit
        }
    }

    private fun oneDecimal(value: Double) = String.format(Locale.US, "%.1f", value)

    private val HOUR_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
    private val HEAVY_PRECIPITATION_CODES = setOf(63, 65, 67, 73, 75, 82, 86)
    private const val MODERATE_WIND_MS = 6.0
    private const val MODERATE_GUST_MS = 9.0
    private const val MODERATE_WAVE_M = 0.5
    private const val MODERATE_VISIBILITY_KM = 4.0
    private const val ROUGH_WIND_MS = 10.0
    private const val ROUGH_GUST_MS = 14.0
    private const val ROUGH_WAVE_M = 1.0
    private const val ROUGH_VISIBILITY_KM = 1.0
}
