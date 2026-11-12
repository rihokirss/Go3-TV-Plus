package ee.local.go3tvplus.catalog

import android.content.Context
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ee.local.go3tvplus.TvApplication
import ee.local.go3tvplus.data.Go3HttpGateway
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Signed-in account test: creates one previously absent bookmark and removes only that bookmark. */
@RunWith(AndroidJUnit4::class)
class CatalogHistoryAccountDeviceTest {
    @Test fun realAccountAcceptsAppProgressAndImportsAnotherDevicesRewind(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as TvApplication).container
        val profile = app.preferences.selectedProfile() ?: error("A signed-in profile is required")
        val token = app.auth.accessToken()
        val repository = app.catalog
        val dao = repository.dao
        assertTrue("Run this isolated account check before playback tests", dao.historyQueue(profile).first().isEmpty())
        val otherDevice = Go3HttpGateway(context)
        val before = otherDevice.catalogHistory(token, profile).mapTo(hashSetOf()) { it.id }
        repository.syncHistory(profile, true)
        val entitled = dao.rights(profile).first().mapTo(hashSetOf()) { it.id }
        val refs = dao.revisions(profile).first().filter { it.kind == CatalogKind.MOVIE.name && it.id !in before && it.id in entitled }
        val movie = dao.titlesByIds(profile, refs.take(120).map { it.id }).mapNotNull { parseCatalogTitle(Json.parseToJsonElement(it.json).jsonObject) }
            .first { (it.durationSeconds ?: 0) > 180 }
        val oldLocal = dao.progressItem(profile, movie.id)
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val root = "https://go3.ee/api/".toHttpUrl()
        fun request(path: String): Request.Builder = Request.Builder().url(root.newBuilder().addPathSegments(path)
            .addQueryParameter("platform", "ANDROID_TV").addQueryParameter("lang", "ET").addQueryParameter("tenant", "OM_EE").build())
            .header("Accept", "application/json").header("User-Agent", "Go3-Air-account-verification")
            .header("API-AppVersion", "1.36.1-(561)").header("API-Authentication", token).header("API-ProfileUid", profile)
            .header("API-DeviceUid", Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID))
        // Keep credentials in this process, never in logs or host-side files.
        val subscriber = withContext(Dispatchers.IO) { client.newCall(request("subscribers/detail").build()).execute().use {
            assertTrue(it.isSuccessful); Json.parseToJsonElement(it.body.string()).jsonObject
        } }
        val signing = subscriber.array("profiles").map { it.jsonObject }.first { it.text("uid") == profile || it.text("id") == profile }
        val marker = context.getSharedPreferences("probe_history_cleanup", Context.MODE_PRIVATE)
        marker.edit().putString("id", movie.id).putString("profile_uid", profile).commit()
        var attemptedWrite = false
        try {
            attemptedWrite = true
            dao.saveLocalProgress(ProgressEntity(profile, movie.id, null, 64_000, movie.durationSeconds!! * 1000, System.currentTimeMillis()))
            repository.syncHistory(profile)
            assertTrue(dao.historyQueue(profile).first().isEmpty())
            assertEquals(64_000L, otherDevice.catalogHistory(token, profile).first { it.id == movie.id }.positionMs)
            android.util.Log.i("CatalogAcceptance", "Go3 account accepted app progress")
            // Ensure the real server modification time is later than the Sony clock.
            delay(6500)
            otherDevice.saveCatalogHistory(token, profile, movie.id, 128_000)
            repository.syncHistory(profile, true)
            assertEquals(128_000L, dao.progressItem(profile, movie.id)!!.positionMs)
            android.util.Log.i("CatalogAcceptance", "Go3 account progress imported from another client")
            dao.saveLocalProgress(ProgressEntity(profile, movie.id, null, 10_000, movie.durationSeconds * 1000, System.currentTimeMillis()))
            repository.syncHistory(profile)
            assertEquals(10_000L, otherDevice.catalogHistory(token, profile).first { it.id == movie.id }.positionMs)
            android.util.Log.i("CatalogAcceptance", "Intentional rewind synchronized")
        } finally {
            if (attemptedWrite) {
                val url = root.newBuilder().addPathSegments("int/subscribers/bookmarks").addQueryParameter("type", "WATCHED")
                    .addQueryParameter("itemId[]", movie.id).addQueryParameter("token", signing.text("token"))
                    .addQueryParameter("profileId", signing.text("id")).addQueryParameter("platform", "ANDROID_TV")
                    .addQueryParameter("lang", "ET").addQueryParameter("tenant", "OM_EE").build()
                val deletion = request("int/subscribers/bookmarks").url(url).delete().build()
                val removed = withContext(Dispatchers.IO) { try { client.newCall(deletion).execute().use { it.isSuccessful || it.code == 404 } } catch (_: Exception) { false } }
                assertTrue("Isolated account test bookmark needs cleanup", removed)
                val after = otherDevice.catalogHistory(token, profile).mapTo(hashSetOf()) { it.id }
                assertFalse(after.contains(movie.id)); assertTrue(after.containsAll(before))
                marker.edit().clear().commit()
                android.util.Log.i("CatalogAcceptance", "Isolated Go3 account test record removed; existing records preserved")
            }
            dao.deleteQueuedHistory(profile, movie.id); dao.deleteProgress(profile, movie.id)
            oldLocal?.let { dao.saveProgress(it) }
        }
    }
}
