package ee.local.go3tvplus.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import ee.local.go3tvplus.domain.DEFAULT_MURASTE_STOP

class PeatusTransitGatewayTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    @Test fun combinesMurasteDirectionsAndSortsRealtimeDepartures() = runTest {
        server.enqueue(MockResponse().setBody(RESPONSE))
        val gateway = PeatusTransitGateway(
            client = OkHttpClient(),
            endpoint = server.url("/graphql").toString(),
            clock = Clock.fixed(Instant.ofEpochSecond(1_000), ZoneOffset.UTC),
        )

        val board = gateway.departures(DEFAULT_MURASTE_STOP)

        assertEquals("Muraste", board.stopName)
        assertEquals(listOf("124", "129"), board.departures.map { it.routeShortName })
        assertEquals("21524-1", board.departures.first().stopCode)
        assertEquals("Suurupi", board.departures.first().origin)
        assertEquals("Balti jaam", board.departures.first().destination)
        assertTrue(board.departures.first().realtime)
        assertFalse(board.departures.last().realtime)
        val requestBody = server.takeRequest().body.readUtf8()
        assertTrue(requestBody.contains("estonia:4641"))
        assertTrue(requestBody.contains("estonia:4642"))
        assertTrue(requestBody.contains("numberOfDepartures: 30"))
    }

    @Test fun groupsPlatformsWithTheSameNearbyStopName() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"data":{"stops":[
              {"gtfsId":"estonia:4641","name":"Muraste","code":"21525-1","lat":59.45578,"lon":24.42633},
              {"gtfsId":"estonia:4642","name":"Muraste","code":"21524-1","lat":59.45549,"lon":24.42803},
              {"gtfsId":"estonia:131548","name":"Muraste kool","code":"47201-1","lat":59.45749,"lon":24.44231}
            ]}}
        """.trimIndent()))
        val gateway = PeatusTransitGateway(
            client = OkHttpClient(),
            endpoint = server.url("/graphql").toString(),
            clock = Clock.fixed(Instant.ofEpochSecond(1_000), ZoneOffset.UTC),
        )

        val results = gateway.searchStops("Muraste")

        assertEquals("Muraste", results.first().name)
        assertEquals(listOf("21524-1", "21525-1"), results.first().platforms.map { it.code })
        assertEquals("Muraste kool", results[1].name)
    }

    private companion object {
        const val RESPONSE = """
            {
              "data": {
                "stops": [
                  {
                    "name": "Muraste",
                    "code": "21525-1",
                    "stoptimesWithoutPatterns": [{
                      "scheduledDeparture": 600,
                      "realtimeDeparture": 600,
                      "realtime": false,
                      "realtimeState": "SCHEDULED",
                      "serviceDay": 1000,
                      "headsign": "Suurupi",
                      "trip": {
                        "route": {"shortName": "129"},
                        "pattern": {"stops": [{"name": "Balti jaam"}, {"name": "Suurupi"}]}
                      }
                    }]
                  },
                  {
                    "name": "Muraste",
                    "code": "21524-1",
                    "stoptimesWithoutPatterns": [{
                      "scheduledDeparture": 400,
                      "realtimeDeparture": 300,
                      "realtime": true,
                      "realtimeState": "UPDATED",
                      "serviceDay": 1000,
                      "headsign": "Balti jaam (train station)",
                      "trip": {
                        "route": {"shortName": "124"},
                        "pattern": {"stops": [{"name": "Suurupi"}, {"name": "Balti jaam (train station)"}]}
                      }
                    }]
                  }
                ]
              }
            }
        """
    }
}
