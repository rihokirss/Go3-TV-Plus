package ee.local.go3tvplus.catalog

import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ee.local.go3tvplus.data.local.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogStorageDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun migrationKeepsTvAndCreatesProfileIsolatedSearchAndMemory() = runBlocking {
        val name = "catalog-migration-test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("catalog-migration-from-4.json").bufferedReader().readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO channels VALUES ('tv','Test TV',NULL,7,1,0)")
            old.execSQL("INSERT INTO programs VALUES ('p','tv','Programme',NULL,1,2,1,NULL,NULL,NULL,NULL)")
            old.execSQL("INSERT INTO watch_now_matches VALUES ('a','p','key','[]',0,1)")
            old.version = 4
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6).build()
        try {
            assertEquals("Test TV", db.tvDao().observeChannels().first().single().name)
            assertEquals(1, db.tvDao().countPrograms())
            db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE name='watch_now_matches'").use { assertEquals(0, it.count) }
            val dao = db.catalogDao()
            dao.saveTitles(listOf(CatalogEntity(profileId = "a", id = "one", kind = "MOVIE", normalizedText = normalizeCatalogText("Öö õhtu Original"), json = "{}", seriesId = null, seasonId = null, detailed = false, updatedAt = 1)))
            assertEquals(listOf("one"), dao.search("a", catalogSearchQuery("oo oht")!!))
            assertTrue(dao.search("b", catalogSearchQuery("oo")!!).isEmpty())
            dao.add(WatchlistEntity("a", "one", 1))
            dao.saveProgress(ProgressEntity("a", "one", null, 50, 100, 1))
            assertEquals(1, dao.watchlist("a").first().size)
            assertTrue(dao.watchlist("b").first().isEmpty()); assertTrue(dao.progress("b").first().isEmpty())
            assertEquals(50L, dao.progress("a").first().single().positionMs)
            // REPLACE must update the external-content FTS triggers as well.
            dao.saveTitles(listOf(CatalogEntity(profileId = "a", id = "one", kind = "MOVIE", normalizedText = "new title", json = "{}", seriesId = null, seasonId = null, detailed = true, updatedAt = 2)))
            assertTrue(dao.search("a", catalogSearchQuery("oo")!!).isEmpty())
            assertEquals(listOf("one"), dao.search("a", catalogSearchQuery("new")!!))
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun schemaFiveProgressSurvivesAndQueuesUploadAcrossReopen() = runBlocking {
        val name = "catalog-history-migration-test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("catalog-migration-from-5.json").bufferedReader().readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO catalog_progress VALUES ('a','one',NULL,50000,100000,42)")
            old.version = 5
        }
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_5_6).build()
        try {
            assertEquals(50_000L, db.catalogDao().progressItem("a", "one")!!.positionMs)
            assertEquals(1, db.catalogDao().historyQueue("a").first().size)
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals(50_000L, db.catalogDao().historyQueue("a").first().single().positionMs)
            assertTrue(db.catalogDao().historyQueue("b").first().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Suppress("DEPRECATION")
    @Test fun sonyAndroidNineArchiveSigningInfoUsesCombinedFlags() {
        val manager = context.packageManager
        val file = java.io.File(context.cacheDir, "signature-regression.apk")
        java.io.File(context.applicationInfo.sourceDir).copyTo(file, true)
        try {
            val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
            val archive = manager.getPackageArchiveInfo(file.path, flags)!!
            val installed = manager.getPackageInfo(context.packageName, flags)
            assertNotNull(archive.signingInfo)
            assertEquals(installed.signingInfo!!.apkContentsSigners.toSet(), archive.signingInfo!!.apkContentsSigners.toSet())
        } finally { file.delete() }
    }
}
