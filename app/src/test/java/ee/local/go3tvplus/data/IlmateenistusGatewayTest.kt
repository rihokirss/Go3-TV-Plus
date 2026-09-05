package ee.local.go3tvplus.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

class IlmateenistusGatewayTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    @Test fun stationPickerLoadsExactNamesAndSkipsInvalidCoordinates() = runTest {
        server.enqueue(MockResponse().setBody("""
            <observations>
              <station><name>Tilgu</name><latitude>59.45</latitude><longitude>24.48</longitude><watertemperature>16</watertemperature></station>
              <station><name>Naissaare</name><latitude>59.54</latitude><longitude>24.56</longitude><windspeed>0</windspeed></station>
              <station><name>Puuduv</name><latitude></latitude><longitude>24</longitude></station>
              <station><name>Vigane</name><latitude>100</latitude><longitude>24</longitude></station>
              <station><name>Tilgu</name><latitude>59.45</latitude><longitude>24.48</longitude><windspeed>2</windspeed></station>
              <station><name>Tooma kaev</name><latitude>58.87</latitude><longitude>26.26</longitude><windspeed></windspeed></station>
            </observations>
        """.trimIndent()))
        val gateway = IlmateenistusGateway(client = OkHttpClient(), observationsUrl = server.url("/observations.php").toString())
        val stations = gateway.stations()
        assertEquals(listOf("Naissaare", "Tilgu"), stations.map { it.stationName })
        assertEquals(59.45, stations.last().latitude, 0.0001)
        assertEquals(24.48, stations.last().longitude, 0.0001)
    }

    @Test fun parsesRequestedStationsAndSkipsEmptyFields() = runTest {
        server.enqueue(MockResponse().setBody(XML))
        val gateway = IlmateenistusGateway(client = OkHttpClient(), observationsUrl = server.url("/observations.php").toString())

        val observations = gateway.observations(setOf("Tilgu", "Naissaare"))

        assertEquals(setOf("Tilgu", "Naissaare"), observations.keys)
        val tilgu = observations.getValue("Tilgu")
        assertEquals(Instant.ofEpochSecond(1_788_369_320L), tilgu.observedAt)
        assertEquals(17.2, tilgu.airTemperatureC!!, 0.01)
        assertEquals(221, tilgu.windDirectionDegrees)
        assertEquals(2.0, tilgu.windSpeedMs!!, 0.01)
        assertEquals(3.5, tilgu.windGustMs!!, 0.01)
        assertEquals(17.9, tilgu.waterTemperatureC!!, 0.01)
        assertEquals(32, tilgu.waterLevelCm)
        val naissaar = observations.getValue("Naissaare")
        assertEquals(7.5, naissaar.windGustMs!!, 0.01)
        assertNull(naissaar.airTemperatureC)
        assertNull(naissaar.waterTemperatureC)
    }

    private companion object {
        const val XML = """<?xml version="1.0" encoding="UTF-8"?>
<observations timestamp="1788369320">
  <station>
    <name>Tallinn-Harku</name><airtemperature>16.2</airtemperature><windspeed>2</windspeed>
  </station>
  <station>
    <name>Naissaare</name>
    <wmocode>26034</wmocode>
    <airtemperature></airtemperature>
    <winddirection>247</winddirection>
    <windspeed>3.3</windspeed>
    <windspeedmax>7.5</windspeedmax>
  </station>
  <station>
    <name>Tilgu</name>
    <wmocode>97860</wmocode>
    <airtemperature>17.2</airtemperature>
    <winddirection>221</winddirection>
    <windspeed>2</windspeed>
    <windspeedmax>3.5</windspeedmax>
    <waterlevel>32</waterlevel>
    <watertemperature>17.9</watertemperature>
  </station>
</observations>"""
    }
}
