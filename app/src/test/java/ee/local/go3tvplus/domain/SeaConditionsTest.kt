package ee.local.go3tvplus.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SeaConditionsTest {
    private val base = LocalDateTime.of(2026, 9, 3, 10, 0)

    @Test fun calmSeaIsSuitable() {
        assertEquals(SeaCondition.CALM, SeaConditions.rate(hour(wind = 4.0, gust = 6.0, wave = 0.3)))
    }

    @Test fun freshWindOrWavesNeedCaution() {
        assertEquals(SeaCondition.MODERATE, SeaConditions.rate(hour(wind = 7.5, gust = 9.0, wave = 0.4)))
        assertEquals(SeaCondition.MODERATE, SeaConditions.rate(hour(wind = 4.0, gust = 6.0, wave = 0.7)))
        assertEquals(SeaCondition.MODERATE, SeaConditions.rate(hour(wind = 4.0, gust = 6.0, wave = 0.3, visibility = 2.5)))
    }

    @Test fun strongWindThunderOrFogIsUnsuitable() {
        assertEquals(SeaCondition.ROUGH, SeaConditions.rate(hour(wind = 11.0, gust = 15.0, wave = 0.9)))
        assertEquals(SeaCondition.ROUGH, SeaConditions.rate(hour(wind = 3.0, gust = 4.0, wave = 0.2, code = 95)))
        assertEquals(SeaCondition.ROUGH, SeaConditions.rate(hour(wind = 3.0, gust = 4.0, wave = 0.2, visibility = 0.5)))
        assertEquals(SeaCondition.ROUGH, SeaConditions.rate(hour(wind = 5.0, gust = 7.0, wave = 1.2)))
    }

    @Test fun summaryNamesTheFirstChange() {
        val hours = listOf(
            hour(wind = 4.0, gust = 5.0, wave = 0.3),
            hour(wind = 5.0, gust = 6.0, wave = 0.4, offsetHours = 1),
            hour(wind = 11.0, gust = 15.0, wave = 0.9, offsetHours = 2),
        )
        val summary = SeaConditions.summary(hours)
        assertTrue(summary, summary.startsWith("Praegu sobib sõiduks: tuul 4.0 m/s, laine 0.3 m."))
        assertTrue(summary, summary.contains("Halveneb kell 12:00: tuul 11.0 m/s (puhangud 15.0), laine 0.9 m."))
    }

    @Test fun summaryReportsSteadyConditions() {
        val hours = listOf(hour(wind = 12.0, gust = 16.0, wave = 1.1), hour(wind = 12.0, gust = 16.0, wave = 1.2, offsetHours = 1))
        val summary = SeaConditions.summary(hours)
        assertTrue(summary, summary.startsWith("Praegu merele ei sobi"))
        assertTrue(summary, summary.endsWith("Olukord püsib kogu prognoosi ulatuses."))
    }

    private fun hour(
        wind: Double,
        gust: Double,
        wave: Double?,
        code: Int = 1,
        visibility: Double? = 20.0,
        offsetHours: Long = 0,
    ) = SeaHour(
        time = base.plusHours(offsetHours),
        windSpeedMs = wind,
        windGustMs = gust,
        windDirectionDegrees = 250,
        waveHeightM = wave,
        wavePeriodS = 3.0,
        waveDirectionDegrees = 260,
        visibilityKm = visibility,
        weatherCode = code,
        precipitationProbability = 10,
        temperatureC = 17.0,
    )
}
