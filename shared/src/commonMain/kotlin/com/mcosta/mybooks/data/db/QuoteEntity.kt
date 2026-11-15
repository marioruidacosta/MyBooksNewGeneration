package com.mcosta.mybooks.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.mcosta.mybooks.domain.Quote
import kotlinx.coroutines.flow.Flow

/** A quote from a book ([bookId]); removed with its book by the repository. */
@Entity(tableName = "quotes", indices = [Index(value = ["bookId"]), Index(value = ["uuid"], unique = true)])
data class QuoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val text: String,
    val page: Int?,
    val dateAdded: Long,
    /** Where a highlight is in its ebook (a Readium locator as JSON); null for quotes typed or photographed. */
    val position: String? = null,
    /** The row's id on every device (and on the server). */
    @ColumnInfo(defaultValue = "")
    val uuid: String = newUuid(),
    /** Per field, when it last changed (JSON); drives the sync merge. */
    @ColumnInfo(defaultValue = "{}")
    val fieldTimes: String = "{}",
    /** Deleted: kept as a tombstone until the deletion has synced; screens and backups skip it. */
    @ColumnInfo(defaultValue = "0")
    val deleted: Boolean = false,
)

fun QuoteEntity.toQuote() = Quote(id, bookId, text, page, dateAdded, position)
fun Quote.toEntity() = QuoteEntity(id, bookId, text, page, dateAdded, position)

@Dao
interface QuoteDao {
    /** A book's quotes in page order, quotes without a page last, then oldest first. */
    @Query("SELECT * FROM quotes WHERE bookId = :bookId AND deleted = 0 ORDER BY page IS NULL, page, dateAdded, id")
    fun observeForBook(bookId: Long): Flow<List<QuoteEntity>>

    @Query("SELECT * FROM quotes WHERE bookId = :bookId AND deleted = 0 ORDER BY page IS NULL, page, dateAdded, id")
    suspend fun forBook(bookId: Long): List<QuoteEntity>

    @Query("SELECT * FROM quotes WHERE deleted = 0 ORDER BY bookId, page IS NULL, page, dateAdded, id")
    suspend fun getAll(): List<QuoteEntity>

    /** Every row, deleted ones included: what sync reads. */
    @Query("SELECT * FROM quotes ORDER BY id")
    suspend fun getAllAny(): List<QuoteEntity>

    @Query("SELECT * FROM quotes WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): QuoteEntity?

    @Insert
    suspend fun insert(quote: QuoteEntity): Long

    @Update
    suspend fun update(quote: QuoteEntity)

    @Query("DELETE FROM quotes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM quotes WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)
}
