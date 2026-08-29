package ee.local.go3tvplus.data

import ee.local.go3tvplus.domain.AuthTokens
import ee.local.go3tvplus.domain.Channel
import ee.local.go3tvplus.domain.DeviceAuthState
import ee.local.go3tvplus.domain.DeviceCode
import ee.local.go3tvplus.domain.Go3Gateway
import ee.local.go3tvplus.domain.PlaybackTicket
import ee.local.go3tvplus.domain.Profile
import ee.local.go3tvplus.domain.Program
import ee.local.go3tvplus.domain.TokenStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AuthCoordinatorTest {
    @Test fun pairingStoresApprovedTokens() = runTest {
        val tokenStore = MemoryTokenStore()
        val gateway = FakeGateway()
        val coordinator = AuthCoordinator(gateway, tokenStore, this) { Instant.EPOCH }

        coordinator.start()
        advanceUntilIdle()

        assertEquals(DeviceAuthState.Approved, coordinator.state.value)
        assertEquals("access", tokenStore.load()?.accessToken)
    }

    @Test fun existingTokenStartsApproved() = runTest {
        val tokenStore = MemoryTokenStore().apply {
            save(AuthTokens("saved", null, Instant.MAX))
        }
        val coordinator = AuthCoordinator(FakeGateway(), tokenStore, backgroundScope)
        assertEquals(DeviceAuthState.Approved, coordinator.state.value)
    }
}

private class MemoryTokenStore : TokenStore {
    private var tokens: AuthTokens? = null
    override fun load() = tokens
    override fun save(tokens: AuthTokens) { this.tokens = tokens }
    override fun clear() { tokens = null }
}

private class FakeGateway : Go3Gateway {
    override suspend fun requestDeviceCode() = DeviceCode("123456", "https://example.test", "https://example.test/123456", Instant.EPOCH.plusSeconds(30), 1)
    override suspend fun pollDeviceCode(deviceCode: String) = AuthTokens("access", "refresh", Instant.MAX)
    override suspend fun refreshTokens(refreshToken: String) = error("unused")
    override suspend fun profiles(accessToken: String): List<Profile> = error("unused")
    override suspend fun channels(accessToken: String, profileId: String): List<Channel> = error("unused")
    override suspend fun programs(accessToken: String, profileId: String, from: Instant, until: Instant): List<Program> = error("unused")
    override suspend fun liveTicket(accessToken: String, profileId: String, channelId: String): PlaybackTicket = error("unused")
    override suspend fun catchupTicket(accessToken: String, profileId: String, programId: String): PlaybackTicket = error("unused")
    override suspend fun prolongPlayback(accessToken: String, playbackSessionId: String) = Unit
    override suspend fun closePlayback(accessToken: String, playbackSessionId: String) = Unit
}
