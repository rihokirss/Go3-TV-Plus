package ee.local.go3tvplus.domain

import java.time.Instant

data class TransitStopPlatform(
    val id: String,
    val code: String,
    val latitude: Double,
    val longitude: Double,
)

data class TransitStopSelection(
    val name: String,
    val platforms: List<TransitStopPlatform>,
)

data class TransitDeparture(
    val stopCode: String,
    val routeShortName: String,
    val origin: String,
    val destination: String,
    val scheduledAt: Instant,
    val departureAt: Instant,
    val realtime: Boolean,
    val cancelled: Boolean,
) {
    /** Sama väljumine ka pärast värskendust, kuigi reaalaja väljumisaeg võib nihkuda. */
    fun sameTrip(other: TransitDeparture): Boolean =
        stopCode == other.stopCode && routeShortName == other.routeShortName && scheduledAt == other.scheduledAt
}

data class TransitBoard(
    val stopName: String,
    val departures: List<TransitDeparture>,
    val fetchedAt: Instant,
)

val DEFAULT_MURASTE_STOP = TransitStopSelection(
    name = "Muraste",
    platforms = listOf(
        TransitStopPlatform("estonia:4642", "21524-1", 59.4554864, 24.4280285),
        TransitStopPlatform("estonia:4641", "21525-1", 59.4557814, 24.4263281),
    ),
)
