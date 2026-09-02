package ee.local.go3tvplus.domain

import java.time.Instant

sealed interface DeviceAuthState {
    data object Restoring : DeviceAuthState
    data object Idle : DeviceAuthState
    data object RequestingCode : DeviceAuthState
    data class AwaitingApproval(
        val deviceCode: String,
        val verificationUrl: String,
        val qrPayload: String,
        val expiresAt: Instant,
        val pollIntervalSeconds: Long,
    ) : DeviceAuthState
    data object Approved : DeviceAuthState
    data object Expired : DeviceAuthState
    data class Failed(val message: String, val recoverable: Boolean = true) : DeviceAuthState
}

data class DeviceCode(
    val code: String,
    val verificationUrl: String,
    val qrPayload: String,
    val expiresAt: Instant,
    val pollIntervalSeconds: Long,
)

/** Go3 annab seadmesidumisel ühe pikaealise tokeni; värskendusvoogu teenusel ei ole. */
data class AuthTokens(val accessToken: String)

data class Profile(
    val id: String,
    val name: String,
    val isKids: Boolean,
    val avatarUrl: String? = null,
)

data class Channel(
    val id: String,
    val name: String,
    val logoUrl: String? = null,
    val serverNumber: Int? = null,
    val entitled: Boolean = true,
)

/** Kanalinumber UI-s. Kanalikoguja tagab, et igal olekusse jõudnud kanalil on number olemas. */
val Channel.number: Int get() = serverNumber ?: 0

data class Program(
    val id: String,
    val channelId: String,
    val title: String,
    val description: String? = null,
    val startsAt: Instant,
    val endsAt: Instant,
    val catchupAvailable: Boolean,
)

data class PlaybackTicket(
    val contentId: String,
    val manifestUrl: String,
    val mimeType: String,
    val licenseUrl: String? = null,
    val licenseRequestHeaders: Map<String, String> = emptyMap(),
    val playbackSessionId: String? = null,
    val prolongIntervalSeconds: Long? = null,
    val isLive: Boolean,
)

sealed class Go3Failure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Authentication : Go3Failure("Sisselogimine aegus. Seo konto uuesti.")
    class DeviceLimit : Go3Failure("Go3 seadmete piirang on täis.")
    class StreamLimit : Go3Failure("Kaks samaaegset Go3 striimi on juba kasutusel.")
    class NotEntitled : Go3Failure("See kanal ei kuulu sinu paketti.")
    class GeoBlocked : Go3Failure("Sisu ei ole selles asukohas saadaval.")
    open class Unavailable(message: String, cause: Throwable? = null) : Go3Failure(message, cause)
    class HttpStatus(val statusCode: Int) : Unavailable("Go3 päring ebaõnnestus (HTTP $statusCode)")
}
