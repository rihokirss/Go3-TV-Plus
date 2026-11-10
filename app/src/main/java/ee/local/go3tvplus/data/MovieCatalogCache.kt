package ee.local.go3tvplus.data

import ee.local.go3tvplus.domain.CatalogMovie
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

/** Only complete catalogues are published; profile changes never reuse another profile's results. */
internal class MovieCatalogCache(
    private val scope: CoroutineScope,
    private val load: suspend (String) -> List<CatalogMovie>,
    private val now: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()
    private var profile: String? = null
    private var loadedAt: Instant? = null
    private var index: Map<String, List<CatalogMovie>> = emptyMap()
    private var inFlight: Pair<String, Deferred<Result<List<CatalogMovie>>>>? = null

    suspend fun candidates(profileId: String, externalId: String): List<CatalogMovie> =
        candidates(profileId, setOf(externalId))[externalId].orEmpty()

    suspend fun candidates(profileId: String, externalIds: Set<String>): Map<String, List<CatalogMovie>> = mutex.withLock {
        val at = now()
        if (profile != profileId || loadedAt?.let { Duration.between(it, at) in Duration.ZERO..TTL } != true) {
            // A focus change cancels the waiter, not the shared catalogue download.
            val pending = inFlight?.takeIf { it.first == profileId && !it.second.isCancelled }?.second
                ?: scope.async {
                    try { Result.success(load(profileId)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { Result.failure(error) }
                }.also { inFlight = profileId to it }
            val fresh = try { pending.await().getOrThrow().groupBy(CatalogMovie::externalId) }
            catch (error: Exception) {
                if (pending.isCompleted && inFlight?.second === pending) inFlight = null
                throw error
            }
            index = fresh
            profile = profileId
            loadedAt = now()
            if (inFlight?.second === pending) inFlight = null
        }
        externalIds.associateWith { index[it].orEmpty() }
    }

    private companion object { val TTL: Duration = Duration.ofMinutes(15) }
}
