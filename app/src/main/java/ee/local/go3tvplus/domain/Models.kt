package ee.local.go3tvplus.domain

import java.time.Instant

sealed interface DeviceAuthState {
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

data class AuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Instant,
)

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
    val drmScheme: DrmScheme? = null,
    val licenseUrl: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val licenseRequestHeaders: Map<String, String> = emptyMap(),
    val playbackSessionId: String? = null,
    val prolongIntervalSeconds: Long? = null,
    val isLive: Boolean,
)

enum class DrmScheme { WIDEVINE, PLAYREADY, CLEARKEY }

data class PlaybackSession(
    val id: String,
    val contentId: String,
    val startedAt: Instant,
)

sealed class Go3Failure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConfigured : Go3Failure("Go3 API leping ei ole veel HAR-i põhjal seadistatud.")
    class Authentication : Go3Failure("Sisselogimine aegus. Seo konto uuesti.")
    class DeviceLimit : Go3Failure("Go3 seadmete piirang on täis.")
    class StreamLimit : Go3Failure("Kaks samaaegset Go3 striimi on juba kasutusel.")
    class NotEntitled : Go3Failure("See kanal ei kuulu sinu paketti.")
    class GeoBlocked : Go3Failure("Sisu ei ole selles asukohas saadaval.")
    open class Unavailable(message: String, cause: Throwable? = null) : Go3Failure(message, cause)
    class HttpStatus(val statusCode: Int) : Unavailable("Go3 päring ebaõnnestus (HTTP $statusCode)")
}
