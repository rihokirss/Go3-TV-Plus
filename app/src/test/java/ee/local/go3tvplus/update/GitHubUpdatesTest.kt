package ee.local.go3tvplus.update

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.MessageDigest

class GitHubUpdatesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val payload = "a signed apk would be downloaded here".toByteArray()
    private val digest = MessageDigest.getInstance("SHA-256").digest(payload).hex()
    private val url = "https://github.com/rihokirss/Go3-TV-Plus/releases/download/v0.4.37/Go3-Air-0.4.37.apk"

    private fun metadata(
        tag: String = "v0.4.37", draft: Boolean = false, preview: Boolean = false,
        assetUrl: String = url, hash: String = "sha256:$digest", size: Long = payload.size.toLong(),
    ) = """{"tag_name":"$tag","draft":$draft,"prerelease":$preview,"assets":[
        {"name":"Go3-Air-${tag.removePrefix("v")}.apk","size":$size,
        "digest":"$hash","browser_download_url":"$assetUrl"}]}"""

    private fun client(body: ByteArray, status: Int = 200, length: String? = null) = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").body(body.toResponseBody())
                .apply { if (length != null) header("Content-Length", length) }.build()
        }).build()

    @Test fun comparesVersionsNumericallyAndRejectsPreviewsAndMalformedVersions() {
        assertTrue(newerRelease("v0.4.100", "0.4.37"))
        assertFalse(newerRelease("v0.4.36", "0.4.37"))
        assertFalse(newerRelease("v0.4.37", "0.4.37"))
        assertFalse(newerRelease("v0.4.38-rc1", "0.4.37"))
        assertFalse(newerRelease("v0.4.38junk", "0.4.37"))
        assertFalse(newerRelease("v99999999999999.4.38", "0.4.37"))
    }

    @Test fun previewCanUpgradeToTheMatchingOrNewerStableRelease() {
        assertTrue(newerRelease("v0.5.0", "0.5.0-preview"))
        assertTrue(newerRelease("v0.5.1", "0.5.0-preview"))
        assertFalse(newerRelease("v0.4.38", "0.5.0-preview"))
        assertFalse(newerRelease("v0.5.1-preview", "0.5.0-preview"))
    }

    @Test fun acceptsStableReleaseAndIgnoresDraftPreviewAndInstalledVersions() {
        val release = parseRelease(metadata(), "0.4.36")!!
        assertEquals("0.4.37", release.version)
        assertEquals(digest, release.sha256)
        assertNull(parseRelease(metadata(draft = true), "0.4.36"))
        assertNull(parseRelease(metadata(preview = true), "0.4.36"))
        assertNull(parseRelease(metadata(tag = "v0.4.38-rc1"), "0.4.36"))
        assertNull(parseRelease(metadata(), "0.4.37"))
        assertNull(parseRelease(metadata(), "0.4.38"))
    }

    @Test fun rejectsOtherRepositoriesInsecureUrlsAndAmbiguousAssets() {
        for (badUrl in listOf(url.replace("https:", "http:"), url.replace("rihokirss", "someone-else"),
            url.replace("github.com", "github.com.attacker.test"), "$url?redirect=elsewhere", "$url#fragment")) {
            assertThrows(IOException::class.java) { parseRelease(metadata(assetUrl = badUrl), "0.4.36") }
        }
        val root = metadata()
        val asset = root.substringAfter('[').substringBeforeLast(']')
        val duplicate = root.substringBefore('[') + "[$asset,$asset]}"
        assertThrows(IOException::class.java) { parseRelease(duplicate, "0.4.36") }
    }

    @Test fun rejectsMissingChecksumsAndUnboundedDownloads() {
        for (hash in listOf("", digest, "md5:$digest", "sha256:broken")) {
            assertThrows(IOException::class.java) { parseRelease(metadata(hash = hash), "0.4.36") }
        }
        for (size in listOf(0L, -1L, MAX_UPDATE_BYTES + 1)) {
            assertThrows(IOException::class.java) { parseRelease(metadata(size = size), "0.4.36") }
        }
    }

    @Test fun apiErrorsDoNotLookLikeNoUpdateAvailable() = runTest {
        try {
            GitHubUpdates(client("rate limited".toByteArray(), 403)).latest("0.4.36")
            fail("HTTP error must be visible to a manual check")
        } catch (error: IOException) { assertTrue(error.message!!.contains("403")) }
    }

    @Test fun downloadsAndVerifiesTheExactBytes() = runTest {
        val directory = temporary.newFolder()
        val progress = mutableListOf<Int>()
        val file = GitHubUpdates(client(payload)).download(parseRelease(metadata(), "0.4.36")!!, directory, progress::add)
        assertArrayEquals(payload, file.readBytes())
        assertEquals(100, progress.last())
        assertFalse(directory.resolve("update.apk.part").exists())
    }

    @Test fun checksumFailureDeletesBothPartialAndPreviouslyDownloadedApks() = runTest {
        val directory = temporary.newFolder()
        directory.resolve("update.apk").writeText("previous download")
        try {
            GitHubUpdates(client(ByteArray(payload.size))).download(parseRelease(metadata(), "0.4.36")!!, directory) {}
            fail("Tampered download must fail")
        } catch (_: IOException) { }
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test fun truncatedAndOversizedBodiesAreRejectedAndCleanedUp() = runTest {
        for (body in listOf(payload.dropLast(1).toByteArray(), payload + byteArrayOf(1))) {
            val directory = temporary.newFolder()
            try {
                GitHubUpdates(client(body)).download(parseRelease(metadata(), "0.4.36")!!, directory) {}
                fail("Wrong-sized download must fail")
            } catch (_: IOException) { }
            assertEquals(0, directory.listFiles()!!.size)
        }
    }
}
