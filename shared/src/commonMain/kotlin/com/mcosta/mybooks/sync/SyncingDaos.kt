package com.mcosta.mybooks.sync

import com.mcosta.mybooks.data.db.BookDao
import com.mcosta.mybooks.data.db.BookEntity
import com.mcosta.mybooks.data.db.BookmarkDao
import com.mcosta.mybooks.data.db.BookmarkEntity
import com.mcosta.mybooks.data.db.ChallengeDao
import com.mcosta.mybooks.data.db.ChallengeEntity
import com.mcosta.mybooks.data.db.EbookDao
import com.mcosta.mybooks.data.db.EbookEntity
import com.mcosta.mybooks.data.db.QuoteDao
import com.mcosta.mybooks.data.db.QuoteEntity
import com.mcosta.mybooks.data.db.newUuid
import kotlinx.serialization.json.JsonElement

// Wrappers that stamp per-field times on every local write and queue the row for upload. The incoming entities come from
// domain models and carry a random uuid and empty times, so each write reads the stored row and keeps its uuid.

private fun BookEntity.fields(): Map<String, JsonElement> = toRow(null).fields
private suspend fun QuoteEntity.fields(): Map<String, JsonElement> = toRow { it.toString() }!!.fields
private suspend fun BookmarkEntity.fields(): Map<String, JsonElement> = toRow { it.toString() }!!.fields
private suspend fun EbookEntity.fields(): Map<String, JsonElement> = toRow { it.toString() }!!.fields
private fun ChallengeEntity.fields(): Map<String, JsonElement> = toRow().fields

private fun oldTimes(json: String?): Map<String, Long> = json?.let(::parseTimes).orEmpty()

private fun BookEntity.stampedFrom(old: BookEntity?, now: Long): BookEntity {
    val times = stamp(old?.fields(), fields(), oldTimes(old?.fieldTimes), now).toMutableMap()
    // Two different photos both travel as the "photo" marker, so a cover change is detected on the raw value.
    if (old != null && old.coverUrl != coverUrl) times["cover_url"] = now
    return copy(fieldTimes = timesJson(times))
}

private suspend fun QuoteEntity.stampedFrom(old: QuoteEntity?, now: Long) =
    copy(fieldTimes = timesJson(stamp(old?.fields(), fields(), oldTimes(old?.fieldTimes), now)))

private suspend fun BookmarkEntity.stampedFrom(old: BookmarkEntity?, now: Long) =
    copy(fieldTimes = timesJson(stamp(old?.fields(), fields(), oldTimes(old?.fieldTimes), now)))

private suspend fun EbookEntity.stampedFrom(old: EbookEntity?, now: Long) =
    copy(fieldTimes = timesJson(stamp(old?.fields(), fields(), oldTimes(old?.fieldTimes), now)))

private fun ChallengeEntity.stampedFrom(old: ChallengeEntity?, now: Long) =
    copy(fieldTimes = timesJson(stamp(old?.fields(), fields(), oldTimes(old?.fieldTimes), now)))

class SyncingBookDao(private val raw: BookDao, private val recorder: SyncRecorder) : BookDao by raw {
    override suspend fun insert(book: BookEntity): Long = recorder.transaction {
        val now = recorder.now()
        val existing = book.isbn?.let { raw.findByIsbnAny(it) }
        if (existing != null && existing.deleted) {
            // The ISBN belongs to a deleted row: bring it back instead of colliding with the unique index.
            val revived = book.copy(id = existing.id, uuid = existing.uuid, deleted = false).stampedFrom(null, now)
            raw.update(revived)
            recorder.record(SyncTable.BOOKS, revived.uuid)
            existing.id
        } else {
            val fresh = book.copy(uuid = newUuid(), deleted = false).stampedFrom(null, now)
            val id = raw.insert(fresh)
            recorder.record(SyncTable.BOOKS, fresh.uuid)
            id
        }
    }

    override suspend fun update(book: BookEntity) = recorder.transaction {
        val stored = raw.getById(book.id) ?: return@transaction
        val next = book.copy(uuid = stored.uuid, deleted = false).stampedFrom(stored, recorder.now())
        raw.update(next)
        if (next.fields() != stored.fields()) recorder.record(SyncTable.BOOKS, next.uuid)
    }

    override suspend fun delete(id: Long) {
        if (!recorder.enabled()) return raw.delete(id)
        recorder.transaction {
            val stored = raw.getById(id) ?: return@transaction
            val next = stored.copy(deleted = true).stampedFrom(stored, recorder.now())
            raw.update(next)
            recorder.record(SyncTable.BOOKS, next.uuid)
        }
    }
}

class SyncingQuoteDao(private val raw: QuoteDao, private val recorder: SyncRecorder) : QuoteDao by raw {
    private suspend fun find(id: Long) = raw.getAllAny().firstOrNull { it.id == id }

    override suspend fun insert(quote: QuoteEntity): Long = recorder.transaction {
        val fresh = quote.copy(uuid = newUuid(), deleted = false).stampedFrom(null, recorder.now())
        val id = raw.insert(fresh)
        recorder.record(SyncTable.QUOTES, fresh.uuid)
        id
    }

    override suspend fun update(quote: QuoteEntity) = recorder.transaction {
        val stored = find(quote.id) ?: return@transaction
        val next = quote.copy(uuid = stored.uuid, deleted = false).stampedFrom(stored, recorder.now())
        raw.update(next)
        if (next.fields() != stored.fields()) recorder.record(SyncTable.QUOTES, next.uuid)
    }

    private suspend fun softDelete(stored: QuoteEntity) {
        if (stored.deleted) return
        val next = stored.copy(deleted = true).stampedFrom(stored, recorder.now())
        raw.update(next)
        recorder.record(SyncTable.QUOTES, next.uuid)
    }

    override suspend fun delete(id: Long) {
        if (!recorder.enabled()) return raw.delete(id)
        recorder.transaction { find(id)?.let { softDelete(it) } }
    }

    override suspend fun deleteForBook(bookId: Long) {
        if (!recorder.enabled()) return raw.deleteForBook(bookId)
        recorder.transaction { raw.getAllAny().filter { it.bookId == bookId }.forEach { softDelete(it) } }
    }
}

class SyncingBookmarkDao(private val raw: BookmarkDao, private val recorder: SyncRecorder) : BookmarkDao by raw {
    override suspend fun insert(bookmark: BookmarkEntity): Long = recorder.transaction {
        val fresh = bookmark.copy(uuid = newUuid(), deleted = false).stampedFrom(null, recorder.now())
        val id = raw.insert(fresh)
        recorder.record(SyncTable.BOOKMARKS, fresh.uuid)
        id
    }

    private suspend fun softDelete(stored: BookmarkEntity) {
        if (stored.deleted) return
        val next = stored.copy(deleted = true).stampedFrom(stored, recorder.now())
        raw.update(next)
        recorder.record(SyncTable.BOOKMARKS, next.uuid)
    }

    override suspend fun delete(id: Long) {
        if (!recorder.enabled()) return raw.delete(id)
        recorder.transaction { raw.getAllAny().firstOrNull { it.id == id }?.let { softDelete(it) } }
    }

    override suspend fun deleteForBook(bookId: Long) {
        if (!recorder.enabled()) return raw.deleteForBook(bookId)
        recorder.transaction { raw.getAllAny().filter { it.bookId == bookId }.forEach { softDelete(it) } }
    }
}

/** [books] gives each ebook the uuid of its book: an ebook is identified by its book everywhere. */
class SyncingEbookDao(
    private val raw: EbookDao,
    private val books: BookDao,
    private val recorder: SyncRecorder,
) : EbookDao by raw {
    override suspend fun upsert(ebook: EbookEntity) = recorder.transaction {
        val uuid = books.getById(ebook.bookId)?.uuid ?: raw.get(ebook.bookId)?.uuid ?: ebook.uuid
        val stored = raw.getByUuid(uuid)
        val next = ebook.copy(uuid = uuid, deleted = false).stampedFrom(stored, recorder.now())
        raw.upsert(next)
        if (stored == null || next.fields() != stored.fields()) recorder.record(SyncTable.EBOOKS, uuid)
    }

    override suspend fun setPosition(bookId: Long, position: String, lastOpened: Long) = recorder.transaction {
        val stored = raw.get(bookId) ?: return@transaction
        val next = stored.copy(position = position, lastOpened = lastOpened).stampedFrom(stored, recorder.now())
        raw.upsert(next)
        if (next.fields() != stored.fields()) recorder.record(SyncTable.EBOOKS, next.uuid)
    }

    override suspend fun delete(bookId: Long) {
        if (!recorder.enabled()) return raw.delete(bookId)
        recorder.transaction {
            val stored = raw.get(bookId) ?: return@transaction
            val next = stored.copy(deleted = true).stampedFrom(stored, recorder.now())
            raw.upsert(next)
            recorder.record(SyncTable.EBOOKS, next.uuid)
        }
    }
}

class SyncingChallengeDao(private val raw: ChallengeDao, private val recorder: SyncRecorder) : ChallengeDao by raw {
    override suspend fun upsert(challenge: ChallengeEntity) = recorder.transaction {
        val stored = raw.getByYearAny(challenge.year)
        val next = challenge.copy(deleted = false).stampedFrom(stored, recorder.now())
        raw.upsert(next)
        if (stored == null || next.fields() != stored.fields()) recorder.record(SyncTable.CHALLENGES, next.year.toString())
    }

    override suspend fun delete(year: Int) {
        if (!recorder.enabled()) return raw.delete(year)
        recorder.transaction {
            val stored = raw.getByYearAny(year)?.takeIf { !it.deleted } ?: return@transaction
            val next = stored.copy(deleted = true).stampedFrom(stored, recorder.now())
            raw.upsert(next)
            recorder.record(SyncTable.CHALLENGES, year.toString())
        }
    }
}
