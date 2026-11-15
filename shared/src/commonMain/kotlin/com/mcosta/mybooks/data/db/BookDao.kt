package com.mcosta.mybooks.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.domain.searchKey
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    /** [status] null = all books. [query] must come from [searchPattern]. */
    @Query(
        """
        SELECT * FROM books
        WHERE deleted = 0 AND (:status IS NULL OR status = :status)
          AND (:query = '' OR searchText LIKE '%' || :query || '%' ESCAPE '\')
        ORDER BY title COLLATE NOCASE
        """
    )
    fun observe(status: ReadingStatus?, query: String): Flow<List<BookEntity>>

    /** The books with [isbn] (ISBN-13) and [status] (null = all), for a search by ISBN. */
    @Query("SELECT * FROM books WHERE deleted = 0 AND isbn = :isbn AND (:status IS NULL OR status = :status) ORDER BY title COLLATE NOCASE")
    fun observeByIsbn(status: ReadingStatus?, isbn: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id AND deleted = 0")
    fun observeById(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id AND deleted = 0")
    suspend fun getById(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE isbn = :isbn AND deleted = 0")
    suspend fun findByIsbn(isbn: String): BookEntity?

    /** Like [findByIsbn] but also finds a deleted book (its ISBN is still taken). */
    @Query("SELECT * FROM books WHERE isbn = :isbn")
    suspend fun findByIsbnAny(isbn: String): BookEntity?

    @Query("SELECT * FROM books WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): BookEntity?

    /** A book without ISBN with this exact title and authors (case-insensitive), used to de-duplicate imports. */
    @Query(
        "SELECT * FROM books WHERE deleted = 0 AND isbn IS NULL AND title = :title COLLATE NOCASE " +
            "AND authors = :authors COLLATE NOCASE LIMIT 1"
    )
    suspend fun findWithoutIsbn(title: String, authors: String): BookEntity?

    @Query("SELECT * FROM books WHERE deleted = 0 ORDER BY title COLLATE NOCASE")
    suspend fun getAll(): List<BookEntity>

    /** Every row, deleted ones included: what sync reads. */
    @Query("SELECT * FROM books ORDER BY id")
    suspend fun getAllAny(): List<BookEntity>

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)
}

/** Turns user search text into the escaped pattern [BookDao.observe] expects. */
fun searchPattern(q: String): String = escapeLike(searchKey(q.trim()))

/** Escapes LIKE wildcards so user search text matches literally (used with ESCAPE '\'). */
fun escapeLike(q: String): String =
    q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
