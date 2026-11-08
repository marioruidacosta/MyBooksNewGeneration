package com.mcosta.mybooks.sync

import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.db.BookEntity
import com.mcosta.mybooks.data.db.BookmarkEntity
import com.mcosta.mybooks.data.db.ChallengeEntity
import com.mcosta.mybooks.data.db.EbookEntity
import com.mcosta.mybooks.data.db.QuoteEntity
import com.mcosta.mybooks.domain.EbookFormat
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.domain.searchKey
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** A cover photo travels as this marker; the receiver names its own file after the book's uuid. */
private const val PHOTO = "photo"

// ---- helpers ----

private fun s(v: String?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)
private fun n(v: Number?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)

private fun Map<String, JsonElement>.prim(k: String): JsonPrimitive? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }
private fun Map<String, JsonElement>.str(k: String): String? = prim(k)?.contentOrNull
private fun Map<String, JsonElement>.int(k: String): Int? = prim(k)?.intOrNull
private fun Map<String, JsonElement>.long(k: String): Long? = prim(k)?.longOrNull
private fun Map<String, JsonElement>.isDeleted(): Boolean = prim("deleted")?.booleanOrNull ?: false

private fun row(table: SyncTable, id: String, fields: Map<String, JsonElement>, fieldTimes: String, deleted: Boolean): SyncRow =
    SyncRow(table, id, fields + ("deleted" to JsonPrimitive(deleted)), parseTimes(fieldTimes))

// ---- books ----

private fun isLocalCover(ref: String?, store: CoverStore?): Boolean =
    if (store != null) store.isLocal(ref) else ref != null && ref.startsWith(CoverStore.PREFIX)

fun BookEntity.toRow(coverStore: CoverStore?): SyncRow = row(
    SyncTable.BOOKS, uuid,
    mapOf(
        "isbn" to s(isbn), "title" to s(title), "authors" to s(authors), "publisher" to s(publisher),
        "published_year" to n(publishedYear), "page_count" to n(pageCount),
        "cover_url" to s(if (isLocalCover(coverUrl, coverStore)) PHOTO else coverUrl),
        "description" to s(description), "status" to s(status.name), "rating" to n(rating), "notes" to s(notes),
        "date_added" to n(dateAdded), "date_read" to n(dateRead), "date_started" to n(dateStarted),
        "current_page" to n(currentPage), "progress_percent" to n(progressPercent), "review" to s(review),
        "tags" to s(tags), "series" to s(series), "series_number" to s(seriesNumber), "lent_to" to s(lentTo),
        "lent_on" to n(lentOn),
    ),
    fieldTimes, deleted,
)

/** [base] is the local row being updated (keeps its local id and fills a missing status), or null for a new book. */
@Suppress("UNUSED_PARAMETER")
fun SyncRow.toBookEntity(base: BookEntity?, coverStore: CoverStore?): BookEntity {
    val f = fields
    val title = f.str("title") ?: ""
    val authors = f.str("authors") ?: ""
    val cover = f.str("cover_url").let { if (it == PHOTO) CoverStore.PREFIX + "$id.jpg" else it }
    return BookEntity(
        id = base?.id ?: 0,
        isbn = f.str("isbn"),
        title = title, authors = authors,
        publisher = f.str("publisher"),
        publishedYear = f.int("published_year"), pageCount = f.int("page_count"),
        coverUrl = cover, description = f.str("description"),
        status = f.str("status")?.let { name -> ReadingStatus.entries.firstOrNull { it.name == name } }
            ?: base?.status ?: ReadingStatus.TO_READ,
        rating = f.int("rating"), notes = f.str("notes") ?: "",
        dateAdded = f.long("date_added") ?: 0, dateRead = f.long("date_read"),
        searchText = searchKey("$title\n$authors"),
        dateStarted = f.long("date_started"), currentPage = f.int("current_page"),
        progressPercent = f.int("progress_percent"), review = f.str("review") ?: "", tags = f.str("tags") ?: "",
        series = f.str("series"), seriesNumber = f.str("series_number"),
        lentTo = f.str("lent_to"), lentOn = f.long("lent_on"),
        uuid = id, fieldTimes = timesJson(times), deleted = f.isDeleted(),
    )
}

// ---- quotes ----

/** Null when the quote's book has no uuid (gone). */
suspend fun QuoteEntity.toRow(bookUuid: suspend (Long) -> String?): SyncRow? {
    val book = bookUuid(bookId) ?: return null
    return row(
        SyncTable.QUOTES, uuid,
        mapOf("book_id" to s(book), "text" to s(text), "page" to n(page), "date_added" to n(dateAdded), "position" to s(position)),
        fieldTimes, deleted,
    )
}

/** Null when the quote's book isn't on this device (yet). */
suspend fun SyncRow.toQuoteEntity(base: QuoteEntity?, bookId: suspend (String) -> Long?): QuoteEntity? {
    val local = bookId(fields.str("book_id") ?: return null) ?: return null
    return QuoteEntity(
        id = base?.id ?: 0, bookId = local, text = fields.str("text") ?: "", page = fields.int("page"),
        dateAdded = fields.long("date_added") ?: 0, position = fields.str("position"),
        uuid = id, fieldTimes = timesJson(times), deleted = fields.isDeleted(),
    )
}

// ---- bookmarks ----

suspend fun BookmarkEntity.toRow(bookUuid: suspend (Long) -> String?): SyncRow? {
    val book = bookUuid(bookId) ?: return null
    return row(
        SyncTable.BOOKMARKS, uuid,
        mapOf("book_id" to s(book), "position" to s(position), "label" to s(label), "date_added" to n(dateAdded)),
        fieldTimes, deleted,
    )
}

suspend fun SyncRow.toBookmarkEntity(base: BookmarkEntity?, bookId: suspend (String) -> Long?): BookmarkEntity? {
    val local = bookId(fields.str("book_id") ?: return null) ?: return null
    return BookmarkEntity(
        id = base?.id ?: 0, bookId = local, position = fields.str("position") ?: "", label = fields.str("label") ?: "",
        dateAdded = fields.long("date_added") ?: 0,
        uuid = id, fieldTimes = timesJson(times), deleted = fields.isDeleted(),
    )
}

// ---- ebooks (the row id is its book's uuid) ----

suspend fun EbookEntity.toRow(bookUuid: suspend (Long) -> String?): SyncRow? {
    val book = bookUuid(bookId) ?: return null
    return row(
        SyncTable.EBOOKS, book,
        mapOf(
            "format" to s(format.name), "file_name" to s(fileName), "size" to n(size), "date_added" to n(dateAdded),
            "position" to s(position), "last_opened" to n(lastOpened),
        ),
        fieldTimes, deleted,
    )
}

/** The row id is the book's uuid; null when that book isn't on this device (yet) or the format is unknown. */
suspend fun SyncRow.toEbookEntity(base: EbookEntity?, bookId: suspend (String) -> Long?): EbookEntity? {
    val local = bookId(id) ?: return null
    val format = fields.str("format")?.let { name -> EbookFormat.entries.firstOrNull { it.name == name } } ?: base?.format ?: return null
    return EbookEntity(
        bookId = local, format = format, fileName = fields.str("file_name") ?: "", size = fields.long("size") ?: 0,
        dateAdded = fields.long("date_added") ?: 0, position = fields.str("position"), lastOpened = fields.long("last_opened"),
        uuid = id, fieldTimes = timesJson(times), deleted = fields.isDeleted(),
    )
}

// ---- challenges (the row id is the year) ----

fun ChallengeEntity.toRow(): SyncRow = row(SyncTable.CHALLENGES, year.toString(), mapOf("goal" to n(goal)), fieldTimes, deleted)

/** Null when the id isn't a year. */
fun SyncRow.toChallengeEntity(): ChallengeEntity? {
    val year = id.toIntOrNull() ?: return null
    return ChallengeEntity(year, fields.int("goal") ?: 0, timesJson(times), fields.isDeleted())
}

// ---- prefs (not a Room table; the time comes from sync_state) ----

/** A preference as a row; a null [value] is a deleted preference. */
fun prefRow(key: String, value: String?, time: Long): SyncRow = SyncRow(
    SyncTable.PREFS, key,
    mapOf("value" to JsonPrimitive(value ?: ""), "deleted" to JsonPrimitive(value == null)),
    mapOf("value" to time, "deleted" to time),
)

/** A pref row's value, or null if it is deleted. */
fun SyncRow.prefValue(): String? = if (fields.isDeleted()) null else fields.str("value") ?: ""
