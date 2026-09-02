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

@Entity(tableName = "programs", indices = [Index("channelId", "startsAtEpochMs")])
data class ProgramEntity(
    @PrimaryKey val id: String,
    val channelId: String,
    val title: String,
    val description: String?,
    val startsAtEpochMs: Long,
    val endsAtEpochMs: Long,
    val catchupAvailable: Boolean,
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

@Database(entities = [ChannelEntity::class, ProgramEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tvDao(): TvDao

    companion object {
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
        ).addMigrations(MIGRATION_1_2).build()
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
)
fun Program.toEntity() = ProgramEntity(
    id, channelId, title, description, startsAt.toEpochMilli(), endsAt.toEpochMilli(), catchupAvailable,
)
