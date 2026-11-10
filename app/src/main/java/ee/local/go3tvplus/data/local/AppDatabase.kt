package ee.local.go3tvplus.data.local

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

@Entity(tableName = "watch_now_matches", primaryKeys = ["profileId", "programId"])
data class WatchNowMatchEntity(
    val profileId: String,
    val programId: String,
    val requestKey: String,
    val sourcesJson: String,
    val checkedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
)

@Dao
interface TvDao {
    @Query("SELECT * FROM watch_now_matches WHERE profileId = :profileId AND programId IN (:programIds) AND expiresAtEpochMs > :nowMs")
    suspend fun watchNowMatches(profileId: String, programIds: List<String>, nowMs: Long): List<WatchNowMatchEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveWatchNowMatches(matches: List<WatchNowMatchEntity>)

    @Query("DELETE FROM watch_now_matches WHERE profileId = :profileId AND programId IN (:programIds)")
    suspend fun deleteWatchNowMatches(profileId: String, programIds: List<String>)

    @Query("DELETE FROM watch_now_matches WHERE expiresAtEpochMs <= :nowMs")
    suspend fun pruneWatchNowMatches(nowMs: Long)

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

@Database(entities = [ChannelEntity::class, ProgramEntity::class, WatchNowMatchEntity::class], version = 4, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tvDao(): TvDao

    companion object {
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
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
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
