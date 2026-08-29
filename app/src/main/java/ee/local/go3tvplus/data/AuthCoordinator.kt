package ee.local.go3tvplus.data

import ee.local.go3tvplus.domain.AuthTokens
import ee.local.go3tvplus.domain.DeviceAuthState
import ee.local.go3tvplus.domain.Go3Gateway
import ee.local.go3tvplus.domain.TokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

class AuthCoordinator(
    private val gateway: Go3Gateway,
    private val tokenStore: TokenStore,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
) {
    private val mutableState = MutableStateFlow<DeviceAuthState>(
        if (tokenStore.load() != null) DeviceAuthState.Approved else DeviceAuthState.Idle,
    )
    val state: StateFlow<DeviceAuthState> = mutableState.asStateFlow()
    private var authJob: Job? = null

    fun start() {
        authJob?.cancel()
        authJob = scope.launch {
            mutableState.value = DeviceAuthState.RequestingCode
            try {
                val code = gateway.requestDeviceCode()
                mutableState.value = DeviceAuthState.AwaitingApproval(
                    code.code,
                    code.verificationUrl,
                    code.qrPayload,
                    code.expiresAt,
                    code.pollIntervalSeconds,
                )
                while (clock().isBefore(code.expiresAt)) {
                    gateway.pollDeviceCode(code.code)?.let { approved ->
                        tokenStore.save(approved)
                        mutableState.value = DeviceAuthState.Approved
                        return@launch
                    }
                    delay(code.pollIntervalSeconds.coerceAtLeast(1) * 1_000)
                }
                mutableState.value = DeviceAuthState.Expired
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = DeviceAuthState.Failed(error.message ?: "Sidumine ebaõnnestus")
            }
        }
    }

    fun signOut() {
        authJob?.cancel()
        tokenStore.clear()
        mutableState.value = DeviceAuthState.Idle
    }

    suspend fun validTokens(): AuthTokens {
        val current = tokenStore.load() ?: error("Konto ei ole seotud")
        if (current.expiresAt.isAfter(clock().plusSeconds(60))) return current
        val refresh = current.refreshToken ?: error("Sisselogimine aegus")
        return gateway.refreshTokens(refresh).also(tokenStore::save)
    }
}
