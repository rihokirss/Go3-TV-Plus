package ee.local.go3tvplus.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class OpenMeteoWeatherGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var gateway: OpenMeteoWeatherGateway

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        gateway = OpenMeteoWeatherGateway(
            client = OkHttpClient(),
            geocodingBaseUrl = server.url("/search").toString(),
            forecastBaseUrl = server.url("/forecast").toString(),
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test fun parsesEstonianLocationAndNarrowsSearchToEstonia() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"results":[{"name":"Suurupi","latitude":59.46255,"longitude":24.39193,
            "country_code":"EE","admin1":"Harju maakond","admin2":"Harku vald"}]}
        """.trimIndent()))

        val result = gateway.searchLocations("Suurupi").single()

        assertEquals("Suurupi", result.name)
        assertEquals("Harku vald, Harju maakond", result.area)
        assertEquals("EE", server.takeRequest().requestUrl?.queryParameter("countryCode"))
    }

    @Test fun parsesCurrentConditionsAndHourlyForecast() = runTest {
        server.enqueue(MockResponse().setBody("""
            {
              "current": {
                "temperature_2m": 16.4, "apparent_temperature": 15.1,
                "relative_humidity_2m": 78, "precipitation": 0.2,
                "weather_code": 61, "is_day": 1, "wind_speed_10m": 4.3,
                "wind_direction_10m": 247, "wind_gusts_10m": 8.7
              },
              "hourly": {
                "time": ["2026-08-31T21:00", "2026-08-31T22:00"],
                "temperature_2m": [16.0, 15.5],
                "precipitation_probability": [40, 55],
                "weather_code": [61, 63]
              },
              "daily": {
                "time": ["2026-08-31", "2026-09-01"],
                "weather_code": [61, 3],
                "temperature_2m_min": [11.2, 10.8],
                "temperature_2m_max": [17.4, 18.9]
              }
            }
        """.trimIndent()))
        val location = ee.local.go3tvplus.domain.WeatherLocation("Suurupi", "Harku vald", 59.46, 24.39)

        val forecast = gateway.forecast(location)

        assertEquals(16.4, forecast.current.temperatureC, 0.01)
        assertEquals(4.3, forecast.current.windSpeedMs, 0.01)
        assertEquals(2, forecast.hours.size)
        assertEquals(55, forecast.hours[1].precipitationProbability)
        assertEquals(2, forecast.days.size)
        assertEquals(18.9, forecast.days[1].maximumTemperatureC, 0.01)
        assertEquals("auto", server.takeRequest().requestUrl?.queryParameter("timezone"))
    }
}
