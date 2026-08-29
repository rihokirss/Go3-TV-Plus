package ee.local.go3tvplus.data

import ee.local.go3tvplus.domain.AuthTokens
import ee.local.go3tvplus.domain.Channel
import ee.local.go3tvplus.domain.DeviceCode
import ee.local.go3tvplus.domain.Go3Failure
import ee.local.go3tvplus.domain.Go3Gateway
import ee.local.go3tvplus.domain.PlaybackTicket
import ee.local.go3tvplus.domain.Profile
import ee.local.go3tvplus.domain.Program
import java.time.Instant

/**
 * Production is deliberately fail-closed until the user's HAR establishes the real contract.
 * Do not replace this with guessed endpoints or copied bearer tokens.
 */
class UnconfiguredGo3Gateway : Go3Gateway {
    private fun unavailable(): Nothing = throw Go3Failure.NotConfigured()
    override suspend fun requestDeviceCode(): DeviceCode = unavailable()
    override suspend fun pollDeviceCode(deviceCode: String): AuthTokens? = unavailable()
    override suspend fun refreshTokens(refreshToken: String): AuthTokens = unavailable()
    override suspend fun profiles(accessToken: String): List<Profile> = unavailable()
    override suspend fun channels(accessToken: String, profileId: String): List<Channel> = unavailable()
    override suspend fun programs(accessToken: String, profileId: String, from: Instant, until: Instant): List<Program> = unavailable()
    override suspend fun liveTicket(accessToken: String, profileId: String, channelId: String): PlaybackTicket = unavailable()
    override suspend fun catchupTicket(accessToken: String, profileId: String, programId: String): PlaybackTicket = unavailable()
    override suspend fun prolongPlayback(accessToken: String, playbackSessionId: String) = unavailable()
    override suspend fun closePlayback(accessToken: String, playbackSessionId: String) = unavailable()
}
