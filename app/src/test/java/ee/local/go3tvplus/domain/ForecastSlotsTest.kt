package ee.local.go3tvplus.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class ForecastSlotsTest {
    private val start = LocalDateTime.of(2026, 9, 3, 8, 0)

    @Test fun steadyWeatherShowsEverySecondHour() {
        val hours = (0 until 24).map { hour(it) }

        val slots = ForecastSlots.select(hours, maxCount = 6, baseStepHours = 2, timeOf = HourlyWeather::time, notableChange = ForecastSlots::notableWeatherChange)

        assertEquals(listOf(8, 10, 12, 14, 16, 18), slots.map { it.time.hour })
    }

    @Test fun weatherChangeInsertsTheInBetweenHour() {
        val hours = (0 until 24).map { index ->
            hour(index, code = if (index == 3) 95 else 1, precipitation = if (index == 3) 80 else 5)
        }

        val slots = ForecastSlots.select(hours, maxCount = 6, baseStepHours = 2, timeOf = HourlyWeather::time, notableChange = ForecastSlots::notableWeatherChange)

        // 11:00 on äike: tuleb nähtavale, kuigi eelmine tulp oli 10:00.
        assertEquals(listOf(8, 10, 11, 12, 14, 16), slots.map { it.time.hour })
    }

    @Test fun windJumpCountsAsAChange() {
        val hours = (0 until 12).map { index -> hour(index, wind = if (index >= 5) 12.0 else 3.0) }

        val slots = ForecastSlots.select(hours, maxCount = 6, baseStepHours = 2, timeOf = HourlyWeather::time, notableChange = ForecastSlots::notableWeatherChange)

        assertEquals(listOf(8, 10, 12, 13, 15, 17), slots.map { it.time.hour })
    }

    private fun hour(offset: Int, code: Int = 1, precipitation: Int = 5, wind: Double = 3.0) = HourlyWeather(
        time = start.plusHours(offset.toLong()),
        temperatureC = 15.0,
        precipitationProbability = precipitation,
        weatherCode = code,
        windSpeedMs = wind,
        windGustMs = wind + 2,
        windDirectionDegrees = 200,
    )
}
