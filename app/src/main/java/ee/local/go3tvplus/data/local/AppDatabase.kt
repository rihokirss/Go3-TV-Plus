package ee.local.go3tvplus.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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

@Entity(tableName = "programs")
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

    @Query("SELECT * FROM programs WHERE endsAtEpochMs >= :fromMs AND startsAtEpochMs <= :untilMs ORDER BY channelId, startsAtEpochMs")
    fun observePrograms(fromMs: Long, untilMs: Long): Flow<List<ProgramEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceChannels(channels: List<ChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replacePrograms(programs: List<ProgramEntity>)

    @Query("DELETE FROM channels")
    suspend fun clearChannels()

    @Query("DELETE FROM programs WHERE endsAtEpochMs < :beforeMs")
    suspend fun prunePrograms(beforeMs: Long)

    @Query("DELETE FROM programs")
    suspend fun clearPrograms()
}

@Database(entities = [ChannelEntity::class, ProgramEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tvDao(): TvDao

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "go3-tv-cache.db",
        ).build()
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
