package com.mcosta.mybooks.data.db

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
@Entity(tableName = "quotes", indices = [Index(value = ["bookId"])])
data class QuoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val text: String,
    val page: Int?,
    val dateAdded: Long,
    /** Where a highlight is in its ebook (a Readium locator as JSON); null for quotes typed or photographed. */
    val position: String? = null,
)

fun QuoteEntity.toQuote() = Quote(id, bookId, text, page, dateAdded, position)
fun Quote.toEntity() = QuoteEntity(id, bookId, text, page, dateAdded, position)

@Dao
interface QuoteDao {
    /** A book's quotes in page order, quotes without a page last, then oldest first. */
    @Query("SELECT * FROM quotes WHERE bookId = :bookId ORDER BY page IS NULL, page, dateAdded, id")
    fun observeForBook(bookId: Long): Flow<List<QuoteEntity>>

    @Query("SELECT * FROM quotes WHERE bookId = :bookId ORDER BY page IS NULL, page, dateAdded, id")
    suspend fun forBook(bookId: Long): List<QuoteEntity>

    @Query("SELECT * FROM quotes ORDER BY bookId, page IS NULL, page, dateAdded, id")
    suspend fun getAll(): List<QuoteEntity>

    @Insert
    suspend fun insert(quote: QuoteEntity): Long

    @Update
    suspend fun update(quote: QuoteEntity)

    @Query("DELETE FROM quotes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM quotes WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)
}
