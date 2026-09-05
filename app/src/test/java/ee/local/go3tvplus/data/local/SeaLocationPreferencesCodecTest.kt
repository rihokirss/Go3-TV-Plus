package ee.local.go3tvplus.data.local

import ee.local.go3tvplus.domain.SeaForecastPosition
import ee.local.go3tvplus.domain.SeaLocationPreferences
import ee.local.go3tvplus.domain.SeaPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class SeaLocationPreferencesCodecTest {
    private val settings = SeaLocationPreferences(
        first = SeaPoint("Esimene", "Jaam 1", 58.0, 23.0),
        second = SeaPoint("Teine", "Jaam 2", 60.0, 25.0),
    )

    @Test fun preservesStationIdsCoordinatesAndForecastChoice() {
        SeaForecastPosition.entries.forEach { position ->
            val value = settings.copy(forecastPosition = position)
            assertEquals(value, SeaLocationPreferencesCodec.decode(SeaLocationPreferencesCodec.encode(value)))
        }
    }

    @Test fun upgradesWithoutSettingsAndInvalidDataKeepUsableDefaults() {
        listOf(null, "", "{}", "broken").forEach {
            assertEquals(SeaLocationPreferences(), SeaLocationPreferencesCodec.decode(it))
        }
        val malformed = SeaLocationPreferencesCodec.encode(settings).replace("58.0", "999.0")
        assertEquals(SeaLocationPreferences(), SeaLocationPreferencesCodec.decode(malformed))
    }

    @Test fun forecastUsesSelectedStationOrMidpointWhileMeasurementsKeepBothStations() {
        val midpoint = settings.route()
        assertEquals(59.0, midpoint.seaLatitude, 0.00001)
        assertEquals(24.0, midpoint.seaLongitude, 0.00001)
        assertEquals(setOf("Jaam 1", "Jaam 2"), midpoint.stationNames)
        val first = settings.copy(forecastPosition = SeaForecastPosition.FIRST).route()
        val second = settings.copy(forecastPosition = SeaForecastPosition.SECOND).route()
        assertEquals(58.0, first.seaLatitude, 0.00001)
        assertEquals(23.0, first.seaLongitude, 0.00001)
        assertEquals(60.0, second.seaLatitude, 0.00001)
        assertEquals(25.0, second.seaLongitude, 0.00001)
    }
}
