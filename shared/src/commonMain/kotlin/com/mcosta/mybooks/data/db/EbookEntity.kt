package com.mcosta.mybooks.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.mcosta.mybooks.domain.Bookmark
import com.mcosta.mybooks.domain.Ebook
import com.mcosta.mybooks.domain.EbookFormat
import kotlinx.coroutines.flow.Flow

/** A book's ebook file ([bookId] is the book's id: one file per book). */
@Entity(tableName = "ebooks")
data class EbookEntity(
    @PrimaryKey val bookId: Long,
    val format: EbookFormat,
    val fileName: String,
    val size: Long,
    val dateAdded: Long,
    val position: String? = null,
    val lastOpened: Long? = null,
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

fun EbookEntity.toEbook() = Ebook(bookId, format, fileName, size, dateAdded, position, lastOpened)
fun Ebook.toEntity() = EbookEntity(bookId, format, fileName, size, dateAdded, position, lastOpened)

@Entity(tableName = "bookmarks", indices = [Index(value = ["bookId"]), Index(value = ["uuid"], unique = true)])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val position: String,
    val label: String,
    val dateAdded: Long,
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

fun BookmarkEntity.toBookmark() = Bookmark(id, bookId, position, label, dateAdded)
fun Bookmark.toEntity() = BookmarkEntity(id, bookId, position, label, dateAdded)

@Dao
interface EbookDao {
    @Query("SELECT * FROM ebooks WHERE bookId = :bookId AND deleted = 0")
    fun observe(bookId: Long): Flow<EbookEntity?>

    @Query("SELECT * FROM ebooks WHERE bookId = :bookId AND deleted = 0")
    suspend fun get(bookId: Long): EbookEntity?

    @Query("SELECT * FROM ebooks WHERE deleted = 0 ORDER BY bookId")
    suspend fun getAll(): List<EbookEntity>

    /** Every row, deleted ones included: what sync reads. */
    @Query("SELECT * FROM ebooks ORDER BY bookId")
    suspend fun getAllAny(): List<EbookEntity>

    @Query("SELECT * FROM ebooks WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): EbookEntity?

    @Upsert
    suspend fun upsert(ebook: EbookEntity)

    @Query("DELETE FROM ebooks WHERE bookId = :bookId")
    suspend fun delete(bookId: Long)

    @Query("UPDATE ebooks SET position = :position, lastOpened = :lastOpened WHERE bookId = :bookId")
    suspend fun setPosition(bookId: Long, position: String, lastOpened: Long)
}

@Dao
interface BookmarkDao {
    /** A book's bookmarks, oldest first. */
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND deleted = 0 ORDER BY dateAdded, id")
    fun observe(bookId: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND deleted = 0 ORDER BY dateAdded, id")
    suspend fun forBook(bookId: Long): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE deleted = 0 ORDER BY bookId, dateAdded, id")
    suspend fun getAll(): List<BookmarkEntity>

    /** Every row, deleted ones included: what sync reads. */
    @Query("SELECT * FROM bookmarks ORDER BY id")
    suspend fun getAllAny(): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): BookmarkEntity?

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Update
    suspend fun update(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)
}
