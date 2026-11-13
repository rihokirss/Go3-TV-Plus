package ee.local.go3tvplus.catalog

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "catalog_titles", indices = [Index(value = ["profileId", "id"], unique = true), Index("profileId", "seriesId")])
data class CatalogEntity(
    @PrimaryKey(autoGenerate = true) val rowid: Long = 0,
    val profileId: String, val id: String, val kind: String, val normalizedText: String,
    val json: String, val seriesId: String?, val seasonId: String?, val detailed: Boolean,
    val updatedAt: Long,
)
@Fts4(contentEntity = CatalogEntity::class)
@Entity(tableName = "catalog_search")
data class CatalogSearchEntity(val normalizedText: String)
@Entity(tableName = "catalog_seasons", primaryKeys = ["profileId", "id"])
data class SeasonEntity(val profileId: String, val id: String, val seriesId: String, val number: Int, val title: String, @ColumnInfo(defaultValue = "0") val episodesLoaded: Boolean = false)
@Entity(tableName = "catalog_rights", primaryKeys = ["profileId", "id"])
data class RightEntity(val profileId: String, val id: String, val till: Long?, val checkedAt: Long)
@Entity(tableName = "catalog_sync")
data class CatalogSyncEntity(@PrimaryKey val profileId: String, val rightsCheckedAt: Long, val catalogCheckedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val genresCheckedAt: Long = 0,
    @ColumnInfo(defaultValue = "'[]'") val popularMovieIds: String = "[]")
@Entity(tableName = "catalog_watchlist", primaryKeys = ["profileId", "id"])
data class WatchlistEntity(val profileId: String, val id: String, val addedAt: Long)
@Entity(tableName = "catalog_progress", primaryKeys = ["profileId", "contentId"])
data class ProgressEntity(val profileId: String, val contentId: String, val seriesId: String?, val positionMs: Long, val durationMs: Long, val updatedAt: Long, @ColumnInfo(defaultValue = "0") val completed: Boolean = false, @ColumnInfo(defaultValue = "1") val continueEligible: Boolean = true, @ColumnInfo(defaultValue = "0") val remoteModifiedAt: Long = 0) {
    fun domain() = CatalogProgress(profileId, contentId, seriesId, positionMs, durationMs, updatedAt, completed, continueEligible)
}
@Entity(tableName = "catalog_history_outbox", primaryKeys = ["profileId", "contentId"])
data class HistoryOutboxEntity(val profileId: String, val contentId: String, val seriesId: String?, val positionMs: Long, val durationMs: Long, val updatedAt: Long, val completed: Boolean) {
    fun progress() = ProgressEntity(profileId, contentId, seriesId, positionMs, durationMs, updatedAt, completed)
}
data class CatalogRevision(val id: String, val rowid: Long, val updatedAt: Long, val kind: String)

@Dao
interface CatalogDao {
    @Query("SELECT * FROM catalog_progress WHERE profileId = :profile") suspend fun progressSnapshot(profile: String): List<ProgressEntity>
    @Query("DELETE FROM catalog_progress WHERE profileId = :profile AND contentId = :id") suspend fun deleteProgress(profile: String, id: String)
    @Query("DELETE FROM catalog_history_outbox WHERE profileId = :profile AND contentId = :id") suspend fun deleteQueuedHistory(profile: String, id: String)
    @Query("SELECT * FROM catalog_progress WHERE profileId = :profile AND contentId = :id") suspend fun progressItem(profile: String, id: String): ProgressEntity?
    @Query("SELECT * FROM catalog_history_outbox WHERE profileId = :profile ORDER BY updatedAt") fun historyQueue(profile: String): Flow<List<HistoryOutboxEntity>>
    @Query("SELECT * FROM catalog_history_outbox WHERE profileId = :profile AND contentId = :id") suspend fun queuedHistory(profile: String, id: String): HistoryOutboxEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun queueHistory(row: HistoryOutboxEntity)
    @Query("DELETE FROM catalog_history_outbox WHERE profileId = :profile AND contentId = :id AND updatedAt = :updated AND positionMs = :position") suspend fun acknowledgeHistory(profile: String, id: String, updated: Long, position: Long)
    @Transaction suspend fun saveLocalProgress(row: ProgressEntity) {
        val knownRemoteVersion = progressItem(row.profileId, row.contentId)?.remoteModifiedAt ?: 0
        saveProgress(row.copy(remoteModifiedAt = knownRemoteVersion))
        queueHistory(HistoryOutboxEntity(row.profileId, row.contentId, row.seriesId, row.positionMs, row.durationMs, row.updatedAt, row.completed))
    }
    @Query("SELECT id, rowid, updatedAt, kind FROM catalog_titles WHERE profileId = :profile ORDER BY rowid") fun revisions(profile: String): Flow<List<CatalogRevision>>
    @Query("SELECT * FROM catalog_titles WHERE profileId = :profile AND id IN (:ids)") suspend fun titlesByIds(profile: String, ids: List<String>): List<CatalogEntity>
    @Query("SELECT * FROM catalog_titles WHERE profileId = :profile ORDER BY rowid") fun titles(profile: String): Flow<List<CatalogEntity>>
    @Query("SELECT * FROM catalog_titles WHERE profileId = :profile AND seriesId = :series AND seasonId = :season") suspend fun episodes(profile: String, series: String, season: String): List<CatalogEntity>
    @Query("SELECT * FROM catalog_titles WHERE profileId = :profile AND id = :id") suspend fun title(profile: String, id: String): CatalogEntity?
    @Query("SELECT catalog_titles.id FROM catalog_titles JOIN catalog_search ON catalog_titles.rowid = catalog_search.rowid WHERE catalog_search MATCH :query AND profileId = :profile") suspend fun search(profile: String, query: String): List<String>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertTitles(rows: List<CatalogEntity>)
    @Update suspend fun updateTitles(rows: List<CatalogEntity>)
    @Transaction suspend fun saveTitles(rows: List<CatalogEntity>) {
        for ((profile, values) in rows.groupBy { it.profileId }) {
            val existing = values.map { it.id }.chunked(120).flatMap { titlesByIds(profile, it) }.associateBy { it.id }
            insertTitles(values.filter { it.id !in existing }.map { it.copy(rowid = 0) })
            updateTitles(values.mapNotNull { row -> existing[row.id]?.let { row.copy(rowid = it.rowid) } })
        }
    }
    @Query("SELECT * FROM catalog_seasons WHERE profileId = :profile AND seriesId = :series ORDER BY number") suspend fun seasons(profile: String, series: String): List<SeasonEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSeasons(rows: List<SeasonEntity>)
    @Query("SELECT episodesLoaded FROM catalog_seasons WHERE profileId = :profile AND seriesId = :series AND id = :season") suspend fun completeSeason(profile: String, series: String, season: String): Boolean?
    @Query("UPDATE catalog_seasons SET episodesLoaded = 1 WHERE profileId = :profile AND id = :season") suspend fun markSeasonComplete(profile: String, season: String)
    @Query("SELECT * FROM catalog_rights WHERE profileId = :profile") fun rights(profile: String): Flow<List<RightEntity>>
    @Query("DELETE FROM catalog_rights WHERE profileId = :profile") suspend fun clearRights(profile: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveRights(rows: List<RightEntity>)
    @Query("SELECT * FROM catalog_sync WHERE profileId = :profile") fun sync(profile: String): Flow<CatalogSyncEntity?>
    @Query("SELECT * FROM catalog_sync WHERE profileId = :profile") suspend fun syncSnapshot(profile: String): CatalogSyncEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSync(row: CatalogSyncEntity)
    @Query("SELECT * FROM catalog_watchlist WHERE profileId = :profile ORDER BY addedAt DESC") fun watchlist(profile: String): Flow<List<WatchlistEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun add(row: WatchlistEntity)
    @Query("DELETE FROM catalog_watchlist WHERE profileId = :profile AND id = :id") suspend fun remove(profile: String, id: String)
    @Query("SELECT * FROM catalog_progress WHERE profileId = :profile ORDER BY updatedAt DESC") fun progress(profile: String): Flow<List<ProgressEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveProgress(row: ProgressEntity)
}
