package ee.local.go3tvplus.domain

import java.time.Instant

interface Go3Gateway {
    suspend fun requestDeviceCode(): DeviceCode
    suspend fun pollDeviceCode(deviceCode: String): AuthTokens?
    suspend fun refreshTokens(refreshToken: String): AuthTokens
    suspend fun profiles(accessToken: String): List<Profile>
    suspend fun channels(accessToken: String, profileId: String): List<Channel>
    suspend fun programs(
        accessToken: String,
        profileId: String,
        from: Instant,
        until: Instant,
    ): List<Program>
    suspend fun liveTicket(accessToken: String, profileId: String, channelId: String): PlaybackTicket
    suspend fun catchupTicket(accessToken: String, profileId: String, programId: String): PlaybackTicket
    suspend fun prolongPlayback(accessToken: String, playbackSessionId: String)
    suspend fun closePlayback(accessToken: String, playbackSessionId: String)
}

interface TokenStore {
    fun load(): AuthTokens?
    fun hasStoredPayload(): Boolean = load() != null
    fun save(tokens: AuthTokens)
    fun clear()
}
