package com.mcosta.mybooks.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A new random (v4) id, lowercase, 8-4-4-4-12: what a synced row is known by on every device. */
@OptIn(ExperimentalUuidApi::class)
fun newUuid(): String = Uuid.random().toString()

/** A row waiting to be uploaded: one entry per ([table], [rowId]); [rowId] is the row's uuid (a challenge's year). */
@Entity(tableName = "sync_queue", indices = [Index(value = ["table", "rowId"], unique = true)])
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val table: String,
    val rowId: String,
    val createdAt: Long,
)

/** Small key/value store for the sync engine (cursors, device id, ...). */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface SyncQueueDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(entry: SyncQueueEntity)

    suspend fun enqueue(table: String, rowId: String, createdAt: Long) =
        enqueue(SyncQueueEntity(table = table, rowId = rowId, createdAt = createdAt))

    @Query("SELECT * FROM sync_queue ORDER BY id LIMIT :limit")
    suspend fun batch(limit: Int): List<SyncQueueEntity>

    @Query("DELETE FROM sync_queue WHERE id IN (:ids)")
    suspend fun remove(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM sync_queue")
    fun count(): Flow<Int>

    @Query("DELETE FROM sync_queue")
    suspend fun clear()
}

@Dao
interface SyncStateDao {
    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: SyncStateEntity)

    suspend fun set(key: String, value: String) = put(SyncStateEntity(key, value))

    @Query("DELETE FROM sync_state")
    suspend fun clear()
}
