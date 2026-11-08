package com.mcosta.mybooks.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.domain.searchKey

@Entity(tableName = "books", indices = [Index(value = ["isbn"], unique = true), Index(value = ["uuid"], unique = true)])
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val isbn: String?,
    val title: String,
    val authors: String,
    val publisher: String?,
    val publishedYear: Int?,
    val pageCount: Int?,
    val coverUrl: String?,
    val description: String?,
    val status: ReadingStatus,
    val rating: Int?,
    val notes: String,
    val dateAdded: Long,
    val dateRead: Long?,
    /** [searchKey] of title and authors; what library search matches against. */
    val searchText: String,
    val dateStarted: Long? = null,
    val currentPage: Int? = null,
    val progressPercent: Int? = null,
    @ColumnInfo(defaultValue = "")
    val review: String = "",
    /** Tags joined by newlines (a tag never has one). */
    @ColumnInfo(defaultValue = "")
    val tags: String = "",
    val series: String? = null,
    val seriesNumber: String? = null,
    val lentTo: String? = null,
    val lentOn: Long? = null,
    /** The book's id on every device (and on the server). */
    @ColumnInfo(defaultValue = "")
    val uuid: String = newUuid(),
    /** Per field, when it last changed (JSON); drives the sync merge. */
    @ColumnInfo(defaultValue = "{}")
    val fieldTimes: String = "{}",
    /** Deleted: kept as a tombstone until the deletion has synced; screens and backups skip it. */
    @ColumnInfo(defaultValue = "0")
    val deleted: Boolean = false,
)

fun BookEntity.toBook() = Book(
    id = id, isbn = isbn, title = title, authors = authors, publisher = publisher,
    publishedYear = publishedYear, pageCount = pageCount, coverUrl = coverUrl, description = description,
    status = status, rating = rating, notes = notes, dateAdded = dateAdded, dateRead = dateRead,
    dateStarted = dateStarted, currentPage = currentPage, progressPercent = progressPercent, review = review,
    tags = tags.split(TAG_SEPARATOR).filter { it.isNotEmpty() }, series = series, seriesNumber = seriesNumber,
    lentTo = lentTo, lentOn = lentOn,
)

fun Book.toEntity() = BookEntity(
    id = id, isbn = isbn, title = title, authors = authors, publisher = publisher,
    publishedYear = publishedYear, pageCount = pageCount, coverUrl = coverUrl, description = description,
    status = status, rating = rating, notes = notes, dateAdded = dateAdded, dateRead = dateRead,
    // Newline separator stops a query from matching across the title/author boundary.
    searchText = searchKey("$title\n$authors"),
    dateStarted = dateStarted, currentPage = currentPage, progressPercent = progressPercent, review = review,
    tags = tags.joinToString(TAG_SEPARATOR), series = series, seriesNumber = seriesNumber, lentTo = lentTo, lentOn = lentOn,
)

private const val TAG_SEPARATOR = "\n"
