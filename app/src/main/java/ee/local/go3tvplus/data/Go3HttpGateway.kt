package ee.local.go3tvplus.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import ee.local.go3tvplus.BuildConfig
import ee.local.go3tvplus.domain.AuthTokens
import ee.local.go3tvplus.domain.Channel
import ee.local.go3tvplus.domain.DeviceCode
import ee.local.go3tvplus.domain.Go3Failure
import ee.local.go3tvplus.domain.Go3Gateway
import ee.local.go3tvplus.domain.PlaybackTicket
import ee.local.go3tvplus.domain.Profile
import ee.local.go3tvplus.domain.Program
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Go3 API adapter based on the contracts captured from the user's own web and TV clients. */
class Go3HttpGateway(context: Context) : Go3Gateway {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val apiBase = BuildConfig.GO3_API_BASE_URL.toHttpUrl()
    private val deviceUid = stableDeviceUid(appContext)
    private val deviceInfo = listOf(
        Build.MODEL,
        Build.BOARD,
        "Android",
        Build.VERSION.RELEASE,
        Build.MANUFACTURER,
        "Go3 Air ${BuildConfig.VERSION_NAME}",
    ).joinToString(";") { it.replace(';', '_') } + ";"
    private val cookieJar = MemoryCookieJar()
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .dns(CachingDns())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile private var tier: String = "TIER_2"
    @Volatile private var channelIds: List<String> = emptyList()
    /** Vana programmiotspunkt vastab 404, kui see on välja lülitatud; jätame selle meelde, et mitte iga värskendusega uuesti proovida. */
    @Volatile private var legacyEndpointAvailable = true

    override suspend fun requestDeviceCode(): DeviceCode {
        val response = executeJson(
            request("subscribers/devices/otc", method = Method.POST, includeTenant = false),
        ).jsonObject
        val code = response.string("code") ?: throw Go3Failure.Unavailable("Go3 ei tagastanud sidumiskoodi")
        val expiresAt = response.instant("till") ?: Instant.now().plusSeconds(10 * 60)
        val connectUrl = BuildConfig.GO3_CONNECT_URL.trimEnd('/')
        return DeviceCode(
            code = code,
            verificationUrl = connectUrl,
            qrPayload = "$connectUrl?code=$code",
            expiresAt = expiresAt,
            pollIntervalSeconds = 7,
        )
    }

    override suspend fun pollDeviceCode(deviceCode: String): AuthTokens? {
        val body = """{"code":"${deviceCode.jsonEscape()}","rememberMe":true}"""
            .toRequestBody(JSON_MEDIA_TYPE)
        val raw = executeRaw(request("subscribers/login", Method.POST, body, includeTenant = false))
        if (raw.code in 200..299) {
            val subscriber = parseJson(raw).jsonObject
            subscriber.string("tier")?.takeIf(String::isNotBlank)?.let { tier = it }
            val token = subscriber.string("token") ?: return null
            return AuthTokens(token)
        }
        if (raw.looksLikeHtml) {
            throw Go3Failure.Unavailable("Go3 turvakontroll ei lubanud sidumispäringut (HTTP ${raw.code})")
        }
        val marker = raw.body.uppercase()
        if (raw.code == 403 || raw.code == 404 || "OTC_NOT_VERIFIED" in marker) return null
        if ("OTC_EXPIRED" in marker || raw.code == 410) throw Go3Failure.Authentication()
        throw failure(raw)
    }

    override suspend fun profiles(accessToken: String): List<Profile> {
        val subscriber = executeJson(request("subscribers/detail", token = accessToken)).jsonObject
        subscriber.string("tier")?.takeIf(String::isNotBlank)?.let { tier = it }
        return subscriber.array("profiles").mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.string("uid") ?: item.string("id") ?: return@mapNotNull null
            Profile(
                id = id,
                name = item.string("name") ?: "Profiil",
                isKids = item.bool("kids") ?: false,
                avatarUrl = item.obj("avatar")?.imageUrl(),
            )
        }.ifEmpty {
            listOf(Profile(subscriber.string("activeProfileId") ?: "default", "Põhiprofiil", false))
        }
    }

    override suspend fun channels(accessToken: String, profileId: String): List<Channel> {
        val root = executeJson(
            request(
                "products/sections/v2/live_tv",
                token = accessToken,
                profileId = profileId,
                query = listOf("tier" to tier, "recommendations" to "false"),
            ),
        )
        val seen = LinkedHashMap<String, Channel>()
        root.asArray().forEach { sectionElement ->
            val section = sectionElement as? JsonObject ?: return@forEach
            section.array("elements").forEach elementLoop@{ wrapped ->
                val item = (wrapped as? JsonObject)?.obj("item") ?: return@elementLoop
                val id = item.string("id") ?: return@elementLoop
                if (item.bool("visibleOnEpg") == false || item.bool("epgDisabled") == true) return@elementLoop
                seen[id] = Channel(
                    id = id,
                    name = item.string("title") ?: "Kanal $id",
                    logoUrl = item.obj("images")?.imageUrl() ?: item.obj("logos")?.imageUrl(),
                    serverNumber = item.int("channelNumber"),
                    entitled = true,
                )
            }
        }
        channelIds = seen.keys.toList()
        if (seen.isEmpty()) throw Go3Failure.Unavailable("Go3 kanalite nimekiri oli tühi")
        return seen.values.toList()
    }

    override suspend fun programs(
        accessToken: String,
        profileId: String,
        from: Instant,
        until: Instant,
    ): List<Program> {
        if (channelIds.isEmpty()) return emptyList()
        val result = LinkedHashMap<String, Program>()
        channelIds.chunked(30).forEach { ids ->
            if (!legacyEndpointAvailable) return@forEach
            val query = buildList {
                ids.forEach { add("liveId[]" to it) }
                add("since" to from.toString())
                add("till" to until.toString())
            }
            val raw = executeRaw(request("products/lives/programmes", token = accessToken, profileId = profileId, query = query))
            if (raw.code == 404) {
                legacyEndpointAvailable = false
            } else {
                parseJson(raw).asArray().forEach { element ->
                    (element as? JsonObject)?.toProgram()?.let { result[it.id] = it }
                }
            }
        }
        if (!legacyEndpointAvailable) {
            result.putAll(programsFromCatalog(accessToken, profileId, from, until).associateBy(Program::id))
        }
        return result.values.toList()
    }

    private suspend fun programsFromCatalog(
        accessToken: String,
        profileId: String,
        from: Instant,
        until: Instant,
    ): List<Program> {
        val allowedChannels = channelIds.toHashSet()
        val result = LinkedHashMap<String, Program>()
        val probe = epgCatalogPage(accessToken, profileId, 0, 1, "DESC")
        val latestStart = probe.array("items").firstOrNull()
            ?.let { it as? JsonObject }
            ?.instant("since")
            ?: return emptyList()
        val totalCount = probe.obj("meta")?.int("totalCount") ?: return emptyList()
        val daysFromLatest = (Duration.between(from, latestStart).toHours().coerceAtLeast(0) / 24 + 2).toInt()
        var firstResult = (totalCount - daysFromLatest * EPG_ESTIMATED_PROGRAMS_PER_DAY)
            .coerceAtLeast(0)
        repeat(MAX_EPG_CATALOG_PAGES) {
            val root = epgCatalogPage(
                accessToken,
                profileId,
                firstResult,
                EPG_CATALOG_PAGE_SIZE,
                "ASC",
            )
            val items = root.array("items")
            if (items.isEmpty()) return result.values.toList()
            val firstStart = (items.firstOrNull() as? JsonObject)?.instant("since")
            if (result.isEmpty() && firstResult > 0 && firstStart?.isAfter(from) == true) {
                firstResult = (firstResult - EPG_CATALOG_PAGE_SIZE).coerceAtLeast(0)
                return@repeat
            }
            var newestStart: Instant? = null
            items.forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                val startsAt = item.instant("since") ?: return@forEach
                if (newestStart == null || startsAt.isAfter(newestStart)) newestStart = startsAt
                val program = item.toProgram() ?: return@forEach
                if (program.channelId in allowedChannels &&
                    program.startsAt.isBefore(until) && program.endsAt.isAfter(from)
                ) {
                    result[program.id] = program
                }
            }
            if (newestStart?.isAfter(until) == true) return result.values.toList()
            firstResult += items.size
        }
        return result.values.toList()
    }

    private suspend fun epgCatalogPage(
        accessToken: String,
        profileId: String,
        firstResult: Int,
        maxResults: Int,
        order: String,
    ): JsonObject {
        var attempt = 0
        while (true) {
            try {
                return executeJson(
                    request(
                        "products/lives/programmes/catalog",
                        token = accessToken,
                        profileId = profileId,
                        query = listOf(
                            "firstResult" to firstResult.toString(),
                            "maxResults" to maxResults.toString(),
                            "sort" to "since",
                            "order" to order,
                            "tier" to tier,
                        ),
                    ),
                ).jsonObject
            } catch (error: Go3Failure.Unavailable) {
                if (attempt >= 2) throw error
                delay(if (attempt++ == 0) 500 else 1_500)
            }
        }
    }

    private fun JsonObject.toProgram(): Program? {
        val catalogueId = string("id") ?: return null
        // EPG catalogue IDs identify schedule entries. Catch-up playback expects the
        // separate recording product ID (for example 12233523 -> 12897015).
        val id = resolveProgramPlaybackId(catalogueId, string("programRecordingId"))
        val channelId = obj("live")?.string("id")
            ?: string("liveId")
            ?: string("channelId")
            ?: return null
        val startsAt = instant("since") ?: return null
        val endsAt = instant("till") ?: return null
        return Program(
            id = id,
            channelId = channelId,
            title = string("title") ?: "Saade",
            description = string("description"),
            startsAt = startsAt,
            endsAt = endsAt,
            catchupAvailable = instant("catchupTill")?.isAfter(Instant.now()) == true,
        )
    }

    override suspend fun liveTicket(
        accessToken: String,
        profileId: String,
        channelId: String,
    ): PlaybackTicket = playbackTicket(accessToken, profileId, channelId, "LIVE", isLive = true)

    override suspend fun catchupTicket(
        accessToken: String,
        profileId: String,
        programId: String,
    ): PlaybackTicket = playbackTicket(accessToken, profileId, programId, "CATCHUP", isLive = false)

    override suspend fun prolongPlayback(accessToken: String, playbackSessionId: String) {
        executeEmpty(request("products/videosessions/$playbackSessionId", Method.PUT, token = accessToken))
    }

    override suspend fun closePlayback(accessToken: String, playbackSessionId: String) {
        executeEmpty(request("products/videosessions/$playbackSessionId", Method.DELETE, token = accessToken))
    }

    private suspend fun playbackTicket(
        token: String,
        profileId: String,
        productId: String,
        videoType: String,
        isLive: Boolean,
    ): PlaybackTicket {
        val configuration = executeJson(
            request(
                "products/$productId/videos/player/configuration",
                token = token,
                profileId = profileId,
                query = listOf("videoType" to videoType, "dai" to "true", "firstInSession" to "true"),
            ),
        ).jsonObject
        val playlistUrl = configuration.string("playlistUrl")
            ?: throw Go3Failure.Unavailable("Go3 ei tagastanud esitusloendit")
        val playlist = executeJson(requestAbsolute(playlistUrl, token, profileId)).jsonObject
        val sources = playlist.obj("sources") ?: throw Go3Failure.Unavailable("Go3 voog puudub")
        val dashUrl = sources.firstSource("DASH") ?: sources.firstSource("DASH_HEVC")
        val hlsUrl = sources.firstSource("HLS") ?: sources.firstSource("HLS_HEVC")
        val manifest = dashUrl ?: hlsUrl ?: throw Go3Failure.Unavailable("Sobivat DASH/HLS voogu ei leitud")
        val licenseUrl = playlist.obj("drm")?.obj("WIDEVINE")?.string("src")
        val session = configuration.obj("videoSession")
        return PlaybackTicket(
            contentId = productId,
            manifestUrl = manifest.absoluteUrl(),
            mimeType = if (dashUrl != null) "application/dash+xml" else "application/x-mpegURL",
            licenseUrl = licenseUrl?.absoluteUrl(),
            licenseRequestHeaders = licenseHeaders(token, profileId, licenseUrl),
            playbackSessionId = session?.string("videoSessionId"),
            prolongIntervalSeconds = session?.long("prolongInterval"),
            isLive = isLive,
        )
    }

    private fun requestAbsolute(url: String, token: String, profileId: String): Request {
        val parsed = url.absoluteUrl().toHttpUrl().newBuilder()
            .setQueryParameter("platform", PLATFORM)
            .setQueryParameter("lang", LANGUAGE)
            .setQueryParameter("tenant", TENANT)
            .build()
        return requestBuilder(parsed, token, profileId).get().build()
    }

    private fun request(
        path: String,
        method: Method = Method.GET,
        body: okhttp3.RequestBody? = null,
        token: String? = null,
        profileId: String? = null,
        query: List<Pair<String, String>> = emptyList(),
        includeTenant: Boolean = true,
    ): Request {
        val url = apiBase.resolve(path)?.newBuilder()
            ?: throw IllegalArgumentException("Vigane API tee")
        url.addQueryParameter("platform", PLATFORM)
        url.addQueryParameter("lang", LANGUAGE)
        if (includeTenant) url.addQueryParameter("tenant", TENANT)
        query.forEach { (name, value) -> url.addQueryParameter(name, value) }
        val builder = requestBuilder(url.build(), token, profileId)
        return when (method) {
            Method.GET -> builder.get().build()
            Method.POST -> builder.post(body ?: EMPTY_BODY).build()
            Method.PUT -> builder.put(body ?: EMPTY_BODY).build()
            Method.DELETE -> builder.delete(body).build()
        }
    }

    private fun requestBuilder(url: HttpUrl, token: String?, profileId: String?): Request.Builder =
        Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Accept-Language", "et-EE,et;q=0.9")
            .header("User-Agent", "Go3-TV-Plus/${BuildConfig.VERSION_NAME} (Android TV ${Build.VERSION.RELEASE})")
            .header("API-AppVersion", API_APP_VERSION)
            .header("API-CorrelationId", "client_${UUID.randomUUID()}")
            .header("API-DeviceUid", deviceUid)
            .header("API-DeviceInfo", deviceInfo)
            .apply {
                token?.takeIf(String::isNotBlank)?.let { header("API-Authentication", it) }
                profileId?.takeIf(String::isNotBlank)?.let { header("API-ProfileUid", it) }
            }

    private fun licenseHeaders(token: String, profileId: String, licenseUrl: String?): Map<String, String> = buildMap {
        put("Content-Type", "application/octet-stream")
        put("API-Authentication", token)
        put("API-DeviceUid", deviceUid)
        put("API-DeviceInfo", deviceInfo)
        put("API-AppVersion", API_APP_VERSION)
        put("API-CorrelationId", "client_${UUID.randomUUID()}")
        if (profileId.isNotBlank()) put("API-ProfileUid", profileId)
        licenseUrl?.absoluteUrl()?.let { url ->
            val cookieHeader = cookieJar.loadForRequest(url.toHttpUrl()).joinToString("; ") { it.toString() }
            if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
        }
    }

    private suspend fun executeJson(request: Request): JsonElement = parseJson(executeRaw(request))

    private suspend fun executeEmpty(request: Request) {
        val raw = executeRaw(request)
        if (raw.code !in 200..299) throw failure(raw)
    }

    private fun parseJson(raw: RawResponse): JsonElement {
        if (raw.code !in 200..299) throw failure(raw)
        return runCatching { json.parseToJsonElement(raw.body) }
            .getOrElse { throw Go3Failure.Unavailable("Go3 vastus ei olnud loetav", it) }
    }

    private suspend fun executeRaw(request: Request): RawResponse = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        val safeTarget = "${request.method} ${request.url.safeLogPath()}"
        if (BuildConfig.DEBUG) Log.i(LOG_TAG, "Go3 päring algas: $safeTarget")
        try {
            client.newCall(request).execute().use { response: Response ->
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                if (BuildConfig.DEBUG) Log.i(LOG_TAG, "Go3 päring lõppes: $safeTarget, HTTP ${response.code}, ${elapsedMs}ms")
                RawResponse(
                    response.code,
                    response.header("Content-Type").orEmpty(),
                    response.body.string(),
                )
            }
        } catch (error: IOException) {
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            if (BuildConfig.DEBUG) Log.w(LOG_TAG, "Go3 päring katkes: $safeTarget, ${error.javaClass.simpleName}, ${elapsedMs}ms")
            val detail = when (error) {
                is UnknownHostException -> "Go3 aadressi ei leitud"
                is SocketTimeoutException -> "Go3 vastus aegus"
                is ConnectException -> "Go3 serveriga ei saanud ühendust"
                is SSLException -> "Go3 turvaühendus ebaõnnestus"
                else -> "Go3 ühendus ebaõnnestus (${error.javaClass.simpleName})"
            }
            throw Go3Failure.Unavailable(detail, error)
        }
    }

    private fun failure(raw: RawResponse): Go3Failure {
        val marker = raw.body.uppercase()
        return when {
            raw.code == 401 || "AUTHENTICATION_REQUIRED" in marker || "HTTP_SESSION_EXPIRED" in marker -> Go3Failure.Authentication()
            "VIDEO_SESSION_LIMIT" in marker || raw.code == 429 -> Go3Failure.StreamLimit()
            "DEVICE_NOT_EXISTS" in marker || "DEVICE_LIMIT" in marker -> Go3Failure.DeviceLimit()
            "ITEM_NOT_PAID" in marker || "ITEM_NOT_AVAILABLE" in marker || raw.code == 402 -> Go3Failure.NotEntitled()
            "GEOIP" in marker || "INVALID_REGION" in marker -> Go3Failure.GeoBlocked()
            raw.looksLikeHtml -> Go3Failure.Unavailable("Go3 turvakontroll blokeeris päringu (HTTP ${raw.code})")
            else -> Go3Failure.HttpStatus(raw.code)
        }
    }

    private fun String.absoluteUrl(): String = when {
        startsWith("//") -> "https:$this"
        startsWith("/") -> apiBase.resolve(this)?.toString() ?: this
        else -> this
    }

    private fun HttpUrl.safeLogPath(): String {
        val path = encodedPath
        val sessionMarker = "/videosessions/"
        return if (sessionMarker in path) {
            path.substringBefore(sessionMarker) + sessionMarker + "{redacted}"
        } else {
            path
        }
    }

    private data class RawResponse(val code: Int, val contentType: String, val body: String) {
        val looksLikeHtml: Boolean
            get() = contentType.contains("text/html", ignoreCase = true) || body.trimStart().startsWith("<")
    }

    private enum class Method { GET, POST, PUT, DELETE }

    private class MemoryCookieJar : CookieJar {
        private val cookies = ConcurrentHashMap<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            this.cookies[url.host] = cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            return cookies[url.host].orEmpty().filter { it.expiresAt > now && it.matches(url) }
        }
    }

    /** Keeps a successful lookup in memory so flaky Android TV DNS cannot interrupt a long EPG refresh. */
    private class CachingDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
        private val addresses = ConcurrentHashMap<String, List<InetAddress>>()

        override fun lookup(hostname: String): List<InetAddress> = try {
            delegate.lookup(hostname).also { resolved ->
                if (resolved.isNotEmpty()) addresses[hostname] = resolved
            }
        } catch (error: UnknownHostException) {
            addresses[hostname] ?: throw error
        }
    }

    private companion object {
        const val PLATFORM = "ANDROID_TV"
        const val LANGUAGE = "ET"
        const val TENANT = "OM_EE"
        const val API_APP_VERSION = "1.36.1-(561)"
        const val EPG_CATALOG_PAGE_SIZE = 2_000
        const val EPG_ESTIMATED_PROGRAMS_PER_DAY = 1_600
        const val MAX_EPG_CATALOG_PAGES = 20
        const val LOG_TAG = "Go3TvNetwork"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(null)

        fun stableDeviceUid(context: Context): String {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf(String::isNotBlank)
                ?.let { return it }
            val preferences = context.getSharedPreferences("go3_device", Context.MODE_PRIVATE)
            return preferences.getString("uid", null) ?: UUID.randomUUID().toString().replace("-", "").also {
                preferences.edit().putString("uid", it).apply()
            }
        }
    }
}

internal fun resolveProgramPlaybackId(catalogueId: String, programRecordingId: String?): String =
    programRecordingId?.takeIf(String::isNotBlank) ?: catalogueId

private fun JsonElement.asArray(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
private fun JsonObject.array(name: String): JsonArray = this[name] as? JsonArray ?: JsonArray(emptyList())
private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
    ?: string(name)?.toIntOrNull()
private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
    ?: string(name)?.toLongOrNull()
private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.instant(name: String): Instant? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: return null
    primitive.longOrNull?.let { epoch -> return Instant.ofEpochMilli(if (epoch < 10_000_000_000L) epoch * 1_000 else epoch) }
    val text = primitive.contentOrNull ?: return null
    return runCatching { Instant.parse(text) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(text).toInstant() }.getOrNull()
}

private fun JsonObject.imageUrl(): String? {
    values.forEach { value ->
        val candidates = when (value) {
            is JsonArray -> value
            is JsonObject -> JsonArray(listOf(value))
            else -> return@forEach
        }
        candidates.forEach { candidate ->
            val image = candidate as? JsonObject ?: return@forEach
            val url = image.string("mainUrl") ?: image.string("miniUrl") ?: image.string("url")
            if (!url.isNullOrBlank()) return if (url.startsWith("//")) "https:$url" else url
        }
    }
    return null
}

private fun JsonObject.firstSource(name: String): String? =
    (this[name] as? JsonArray)?.firstOrNull()?.let { it as? JsonObject }?.string("src")

private fun String.jsonEscape(): String = buildString(length + 8) {
    this@jsonEscape.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
}
