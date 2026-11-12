package ee.local.go3tvplus.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal const val RELEASE_REPOSITORY = "rihokirss/Go3-TV-Plus"
internal const val MAX_UPDATE_BYTES = 64L * 1024 * 1024

data class AppRelease(
    val version: String,
    val apkUrl: String,
    val size: Long,
    val sha256: String,
)

/** Only numbered stable releases participate; a draft or preview can never replace a TV build. */
internal fun releaseVersion(value: String): List<Int>? {
    val match = Regex("v?(\\d+)\\.(\\d+)\\.(\\d+)").matchEntire(value) ?: return null
    return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
}

internal fun newerRelease(candidate: String, installed: String): Boolean {
    val next = releaseVersion(candidate) ?: return false
    val preview = installed.endsWith("-preview")
    val current = releaseVersion(if (preview) installed.removeSuffix("-preview") else installed) ?: return false
    return next.zip(current).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } ?: preview
}

class GitHubUpdates(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.MINUTES)
        .followSslRedirects(false)
        .build(),
    private val endpoint: String = "https://api.github.com/repos/$RELEASE_REPOSITORY/releases/latest",
) {
    suspend fun latest(installedVersion: String): AppRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(endpoint)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Go3-Air/$installedVersion")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Uuenduste kontroll ebaõnnestus (HTTP ${response.code}).")
            val body = response.body
            if (body.contentLength() > 1024 * 1024) throw IOException("Uuenduste vastus on liiga suur.")
            val source = body.source()
            source.request(1024 * 1024 + 1L)
            if (source.buffer.size > 1024 * 1024) throw IOException("Uuenduste vastus on liiga suur.")
            parseRelease(source.readUtf8(), installedVersion)
        }
    }

    suspend fun download(release: AppRelease, directory: File, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            require(release.size in 1..MAX_UPDATE_BYTES)
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Uuenduse salvestamine ebaõnnestus.")
            val partial = File(directory, "update.apk.part")
            val target = File(directory, "update.apk")
            target.delete()
            var completed = false
            try {
                client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Uuenduse allalaadimine ebaõnnestus (HTTP ${response.code}).")
                    if (!response.request.url.isHttps) throw IOException("Uuenduse ühendus pole turvaline.")
                    val body = response.body
                    if (body.contentLength() >= 0 && body.contentLength() != release.size) {
                        throw IOException("Uuenduse faili suurus ei klapi.")
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    var previousProgress = -1
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > release.size) throw IOException("Uuenduse fail on oodatust suurem.")
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                val progress = (total * 100 / release.size).toInt()
                                if (progress != previousProgress) {
                                    previousProgress = progress
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                    if (total != release.size || digest.digest().hex() != release.sha256) {
                        throw IOException("Uuenduse kontrollsumma ei klapi. Palun proovi uuesti.")
                    }
                }
                currentCoroutineContext().ensureActive()
                if (!partial.renameTo(target)) throw IOException("Uuenduse salvestamine ebaõnnestus.")
                completed = true
                target
            } finally {
                partial.delete()
                if (!completed) target.delete()
            }
        }
}

internal fun parseRelease(body: String, installedVersion: String): AppRelease? {
    val root = Json.parseToJsonElement(body).jsonObject
    // Missing flags are rejected as well, rather than treating malformed data as a stable release.
    if (root["draft"]?.jsonPrimitive?.booleanOrNull != false ||
        root["prerelease"]?.jsonPrimitive?.booleanOrNull != false) return null
    val tag = root.text("tag_name") ?: return null
    if (!newerRelease(tag, installedVersion)) return null
    val version = tag.removePrefix("v")
    val filename = "Go3-Air-$version.apk"
    val asset = root["assets"]?.jsonArray?.map { it.jsonObject }
        ?.singleOrNull { it.text("name") == filename } ?: throw IOException("Uue versiooni APK puudub.")
    val size = asset["size"]?.jsonPrimitive?.longOrNull
        ?.takeIf { it in 1..MAX_UPDATE_BYTES } ?: throw IOException("Uuenduse faili suurus pole sobiv.")
    val digest = asset.text("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
        ?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        ?: throw IOException("Uuenduse kontrollsumma puudub.")
    val url = asset.text("browser_download_url")?.toHttpUrlOrNull()
        ?: throw IOException("Uuenduse allalaadimise aadress puudub.")
    if (!url.isHttps || url.host != "github.com" || url.port != 443 ||
        url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null ||
        url.encodedPath != "/$RELEASE_REPOSITORY/releases/download/$tag/$filename") {
        throw IOException("Uuenduse allikas ei ole Go3 Airi GitHubi väljalase.")
    }
    return AppRelease(version, url.toString(), size, digest)
}

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
