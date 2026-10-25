package ee.local.go3tvplus.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherIconTest {
    @Test fun partlyCloudyIsDifferentFromOvercastInBothDayAndNight() {
        for (code in listOf(1, 2)) {
            assertEquals(WeatherKind.PARTLY_DAY, weatherKind(code, true))
            assertEquals(WeatherKind.PARTLY_NIGHT, weatherKind(code, false))
        }
        assertEquals(WeatherKind.CLOUDY, weatherKind(3, true))
        assertEquals(WeatherKind.CLOUDY, weatherKind(3, false))
    }

    @Test fun unknownDataNeverInventsSunshine() {
        for (code in listOf(-1, 4, 100)) assertEquals(WeatherKind.UNKNOWN, weatherKind(code, true))
        assertEquals(WeatherKind.UNKNOWN, weatherKind(0, null))
        assertEquals(WeatherKind.CLOUDY, weatherKind(2, null))
    }

    @Test fun precipitationTakesPrecedenceOverDaylight() {
        for (day in listOf(true, false)) {
            assertEquals(WeatherKind.RAIN, weatherKind(66, day))
            assertEquals(WeatherKind.SNOW, weatherKind(85, day))
            assertEquals(WeatherKind.THUNDERSTORMS, weatherKind(99, day))
            assertEquals(WeatherKind.FOG, weatherKind(48, day))
        }
    }
}
