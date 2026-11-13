package ee.local.go3tvplus.domain

import java.time.Instant
import ee.local.go3tvplus.catalog.*
import kotlinx.serialization.json.JsonObject

interface Go3Gateway {
    suspend fun requestDeviceCode(): DeviceCode
    suspend fun pollDeviceCode(deviceCode: String): AuthTokens?
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
    suspend fun movieTicket(accessToken: String, profileId: String, movieId: String): PlaybackTicket =
        throw Go3Failure.Unavailable("Kataloogivideo pole saadaval")
    suspend fun catalogPage(accessToken: String, profileId: String, offset: Int, limit: Int): CatalogPage = throw Go3Failure.Unavailable("Kataloog ei ole saadaval")
    suspend fun catalogGenrePage(accessToken: String, profileId: String, offset: Int, limit: Int): CatalogPage = throw Go3Failure.Unavailable("Kataloogi žanrid ei ole saadaval")
    suspend fun catalogSearch(accessToken: String, profileId: String, query: String): List<JsonObject> = throw Go3Failure.Unavailable("Võrguotsing ei ole saadaval")
    suspend fun catalogPopularMovies(accessToken: String, profileId: String): List<JsonObject> = emptyList()
    suspend fun catalogDetail(accessToken: String, profileId: String, id: String): JsonObject = throw Go3Failure.Unavailable("Teose andmed puuduvad")
    suspend fun catalogSeasons(accessToken: String, profileId: String, seriesId: String): List<CatalogSeason> = emptyList()
    suspend fun catalogEpisodes(accessToken: String, profileId: String, seriesId: String, seasonId: String): List<JsonObject> = emptyList()
    suspend fun catalogRights(accessToken: String, profileId: String): CatalogRights = throw Go3Failure.Unavailable("Vaatamisõiguste kontroll ebaõnnestus")
    suspend fun catalogHistory(accessToken: String, profileId: String): List<CatalogHistoryRecord> = throw Go3Failure.Unavailable("Go3 vaatamisjärg ei ole saadaval")
    suspend fun saveCatalogHistory(accessToken: String, profileId: String, contentId: String, positionMs: Long) { throw Go3Failure.Unavailable("Go3 vaatamisjärge ei saanud salvestada") }
    suspend fun prolongPlayback(accessToken: String, playbackSessionId: String)
    suspend fun closePlayback(accessToken: String, playbackSessionId: String)
}

interface TokenStore {
    fun load(): AuthTokens?
    fun hasStoredPayload(): Boolean = load() != null
    fun save(tokens: AuthTokens)
    fun clear()
}
