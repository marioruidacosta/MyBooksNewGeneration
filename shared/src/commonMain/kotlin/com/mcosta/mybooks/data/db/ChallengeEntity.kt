package com.mcosta.mybooks.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A year's reading challenge: read [goal] books in [year]. */
@Entity(tableName = "challenges")
data class ChallengeEntity(
    @PrimaryKey val year: Int,
    val goal: Int,
    /** Per field, when it last changed (JSON); drives the sync merge. */
    @ColumnInfo(defaultValue = "{}")
    val fieldTimes: String = "{}",
    /** Deleted: kept as a tombstone until the deletion has synced; screens and backups skip it. */
    @ColumnInfo(defaultValue = "0")
    val deleted: Boolean = false,
)

@Dao
interface ChallengeDao {
    @Query("SELECT * FROM challenges WHERE deleted = 0 ORDER BY year DESC")
    fun observeAll(): Flow<List<ChallengeEntity>>

    @Query("SELECT * FROM challenges WHERE deleted = 0 ORDER BY year DESC")
    suspend fun getAll(): List<ChallengeEntity>

    /** Every row, deleted ones included: what sync reads. */
    @Query("SELECT * FROM challenges ORDER BY year DESC")
    suspend fun getAllAny(): List<ChallengeEntity>

    @Query("SELECT * FROM challenges WHERE year = :year")
    suspend fun getByYearAny(year: Int): ChallengeEntity?

    @Upsert
    suspend fun upsert(challenge: ChallengeEntity)

    @Query("DELETE FROM challenges WHERE year = :year")
    suspend fun delete(year: Int)
}
