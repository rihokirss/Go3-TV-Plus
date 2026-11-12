package ee.local.go3tvplus.data.local

import ee.local.go3tvplus.catalog.*
import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ee.local.go3tvplus.domain.Channel
import ee.local.go3tvplus.domain.Program
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val logoUrl: String?,
    val serverNumber: Int?,
    val entitled: Boolean,
    val sortOrder: Int,
)

@Entity(tableName = "programs", indices = [Index("channelId", "startsAtEpochMs"), Index("secondaryId", "epgProvider")])
data class ProgramEntity(
    @PrimaryKey val id: String,
    val channelId: String,
    val title: String,
    val description: String?,
    val startsAtEpochMs: Long,
    val endsAtEpochMs: Long,
    val catchupAvailable: Boolean,
    val secondaryId: String? = null,
    val epgProvider: String? = null,
    val originalTitle: String? = null,
    val catchupUntilEpochMs: Long? = null,
)

@Dao
interface TvDao {
    @Query("SELECT * FROM channels WHERE entitled = 1 ORDER BY sortOrder")
    fun observeChannels(): Flow<List<ChannelEntity>>

    /** Tabelis hoitakse ainult vahemikku, mida värskendus ise pügab, seega aken on tabel ise. */
    @Query("SELECT * FROM programs ORDER BY channelId, startsAtEpochMs")
    fun observePrograms(): Flow<List<ProgramEntity>>

    @Query("SELECT COUNT(*) FROM programs")
    suspend fun countPrograms(): Int

    @Query("SELECT COUNT(*) FROM programs WHERE epgProvider IS NOT NULL")
    suspend fun countProgramsWithIdentityMetadata(): Int

    @Query("SELECT * FROM programs WHERE secondaryId IN (:identities) AND endsAtEpochMs <= :nowMs")
    suspend fun recordingsWithIdentities(identities: List<String>, nowMs: Long): List<ProgramEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceChannels(channels: List<ChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replacePrograms(programs: List<ProgramEntity>)

    @Query(
        "DELETE FROM programs WHERE channelId = :channelId AND " +
            "startsAtEpochMs = :startsAtMs AND endsAtEpochMs = :endsAtMs",
    )
    suspend fun deleteProgramSlot(channelId: String, startsAtMs: Long, endsAtMs: Long)

    @Query("DELETE FROM programs WHERE endsAtEpochMs > :fromMs AND startsAtEpochMs < :untilMs")
    suspend fun deleteProgramsOverlapping(fromMs: Long, untilMs: Long)

    @Query("DELETE FROM channels")
    suspend fun clearChannels()

    @Query("DELETE FROM programs WHERE endsAtEpochMs < :beforeMs")
    suspend fun prunePrograms(beforeMs: Long)
}

@Database(entities = [ChannelEntity::class, ProgramEntity::class, CatalogEntity::class, CatalogSearchEntity::class, SeasonEntity::class, RightEntity::class, CatalogSyncEntity::class, WatchlistEntity::class, ProgressEntity::class, HistoryOutboxEntity::class], version = 6, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tvDao(): TvDao
    abstract fun catalogDao(): CatalogDao

    companion object {
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE catalog_progress ADD COLUMN completed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE catalog_progress ADD COLUMN continueEligible INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE catalog_progress ADD COLUMN remoteModifiedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE catalog_seasons ADD COLUMN episodesLoaded INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_history_outbox` (`profileId` TEXT NOT NULL, `contentId` TEXT NOT NULL, `seriesId` TEXT, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `completed` INTEGER NOT NULL, PRIMARY KEY(`profileId`, `contentId`))")
                db.execSQL("INSERT INTO catalog_history_outbox SELECT profileId, contentId, seriesId, positionMs, durationMs, updatedAt, completed FROM catalog_progress")
            }
        }
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS watch_now_matches")
                db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_titles` (`rowid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profileId` TEXT NOT NULL, `id` TEXT NOT NULL, `kind` TEXT NOT NULL, `normalizedText` TEXT NOT NULL, `json` TEXT NOT NULL, `seriesId` TEXT, `seasonId` TEXT, `detailed` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_catalog_titles_profileId_id` ON `catalog_titles` (`profileId`, `id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_titles_profileId_seriesId` ON `catalog_titles` (`profileId`, `seriesId`)")
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `catalog_search` USING FTS4(`normalizedText` TEXT NOT NULL, content=`catalog_titles`)")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_catalog_search_BEFORE_UPDATE BEFORE UPDATE ON catalog_titles BEGIN DELETE FROM catalog_search WHERE docid=OLD.rowid; END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_catalog_search_BEFORE_DELETE BEFORE DELETE ON catalog_titles BEGIN DELETE FROM catalog_search WHERE docid=OLD.rowid; END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_catalog_search_AFTER_UPDATE AFTER UPDATE ON catalog_titles BEGIN INSERT INTO catalog_search(docid, normalizedText) VALUES (NEW.rowid, NEW.normalizedText); END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_catalog_search_AFTER_INSERT AFTER INSERT ON catalog_titles BEGIN INSERT INTO catalog_search(docid, normalizedText) VALUES (NEW.rowid, NEW.normalizedText); END")
                db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_seasons` (`profileId` TEXT NOT NULL, `id` TEXT NOT NULL, `seriesId` TEXT NOT NULL, `number` INTEGER NOT NULL, `title` TEXT NOT NULL, PRIMARY KEY(`profileId`, `id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_rights` (`profileId` TEXT NOT NULL, `id` TEXT NOT NULL, `till` INTEGER, `checkedAt` INTEGER NOT NULL, PRIMARY KEY(`profileId`, `id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_sync` (`profileId` TEXT NOT NULL, `rightsCheckedAt` INTEGER NOT NULL, `catalogCheckedAt` INTEGER NOT NULL, PRIMARY KEY(`profileId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_watchlist` (`profileId` TEXT NOT NULL, `id` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`profileId`, `id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_progress` (`profileId` TEXT NOT NULL, `contentId` TEXT NOT NULL, `seriesId` TEXT, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`profileId`, `contentId`))")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `watch_now_matches` (`profileId` TEXT NOT NULL, `programId` TEXT NOT NULL, `requestKey` TEXT NOT NULL, `sourcesJson` TEXT NOT NULL, `checkedAtEpochMs` INTEGER NOT NULL, `expiresAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`profileId`, `programId`))")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE programs ADD COLUMN secondaryId TEXT")
                db.execSQL("ALTER TABLE programs ADD COLUMN epgProvider TEXT")
                db.execSQL("ALTER TABLE programs ADD COLUMN originalTitle TEXT")
                db.execSQL("ALTER TABLE programs ADD COLUMN catchupUntilEpochMs INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_programs_secondaryId_epgProvider` ON `programs` (`secondaryId`, `epgProvider`)")
            }
        }
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_programs_channelId_startsAtEpochMs` " +
                        "ON `programs` (`channelId`, `startsAtEpochMs`)",
                )
            }
        }

        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "go3-tv-cache.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()
    }
}

fun ChannelEntity.toDomain() = Channel(id, name, logoUrl, serverNumber, entitled)
fun Channel.toEntity(sortOrder: Int) = ChannelEntity(id, name, logoUrl, serverNumber, entitled, sortOrder)
fun ProgramEntity.toDomain() = Program(
    id = id,
    channelId = channelId,
    title = title,
    description = description,
    startsAt = Instant.ofEpochMilli(startsAtEpochMs),
    endsAt = Instant.ofEpochMilli(endsAtEpochMs),
    catchupAvailable = catchupAvailable,
    secondaryId = secondaryId,
    epgProvider = epgProvider,
    originalTitle = originalTitle,
    catchupUntil = catchupUntilEpochMs?.let(Instant::ofEpochMilli),
)
fun Program.toEntity() = ProgramEntity(
    id, channelId, title, description, startsAt.toEpochMilli(), endsAt.toEpochMilli(), catchupAvailable,
    secondaryId, epgProvider, originalTitle, catchupUntil?.toEpochMilli(),
)
