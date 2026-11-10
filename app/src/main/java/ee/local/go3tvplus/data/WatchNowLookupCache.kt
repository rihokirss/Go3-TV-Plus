package ee.local.go3tvplus.data

import ee.local.go3tvplus.domain.Program
import ee.local.go3tvplus.domain.WatchNowSource
import ee.local.go3tvplus.domain.WatchNowResolver
import java.time.Duration
import java.time.Instant

/** Includes negative results; cached recordings never cross a profile or channel-permission change. */
internal class WatchNowLookupCache(private val now: () -> Instant = Instant::now) {
    private data class Key(val profile: String, val requestKey: String, val channels: Set<String>)
    private data class Entry(val sources: List<WatchNowSource>, val checkedAt: Instant)
    private val entries = java.util.LinkedHashMap<Key, Entry>(64, 0.75f, true)

    @Synchronized fun get(profile: String, program: Program, channels: Set<String>): List<WatchNowSource>? {
        val key = Key(profile, WatchNowMatchCodec.requestKey(program), channels)
        val cached = entries[key] ?: return null
        val ttl = if (cached.sources.isEmpty()) WatchNowResolver.EMPTY_CACHE_TTL else WatchNowResolver.CACHE_TTL
        if (Duration.between(cached.checkedAt, now()) !in Duration.ZERO..ttl) {
            entries.remove(key)
            return null
        }
        return cached.sources
    }

    @Synchronized fun put(profile: String, program: Program, channels: Set<String>, sources: List<WatchNowSource>, checkedAt: Instant = now()) {
        entries[Key(profile, WatchNowMatchCodec.requestKey(program), channels)] = Entry(sources, checkedAt)
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }

    companion object {
        const val MAX_ENTRIES = 512
    }
}
