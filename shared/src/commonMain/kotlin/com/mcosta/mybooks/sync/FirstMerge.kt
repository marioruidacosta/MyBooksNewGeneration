package com.mcosta.mybooks.sync

import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.db.BookEntity
import com.mcosta.mybooks.data.db.BookmarkEntity
import com.mcosta.mybooks.data.db.EbookEntity
import com.mcosta.mybooks.data.db.QuoteEntity
import com.mcosta.mybooks.data.db.toBook
import com.mcosta.mybooks.data.ebooks.EbookFiles
import com.mcosta.mybooks.domain.EbookFormat
import com.mcosta.mybooks.share.ShareTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** `merge_library` takes at most this many books per call. */
internal const val MERGE_BATCH = 100

/** `sync_state` key set once [owner]'s first sign-in merge is over on this device. */
internal fun mergedKey(owner: String) = "merged:$owner"

/** `sync_state` key of the books already merged (their uuids, a JSON array): what lets a merge resume. */
internal fun mergeDoneKey(owner: String) = "merge-done:$owner"

/**
 * The first sign-in on a device, once per account: joins the device's library to the cloud one.
 * - No local books: nothing to join; the engine's pull brings the cloud library.
 * - Otherwise the books go to `merge_library` [MERGE_BATCH] at a time; the server matches each to a cloud book (or
 *   keeps it as a new one) and answers with its cloud id, which the local row takes. Two local books matched to the
 *   same cloud book become one here too: fields merged per field, quotes, bookmarks, ebook and share state moved, the
 *   extra row removed for good. After each batch the merged uuids are stored, so an interrupted merge resumes there.
 * - Then every quote, ebook, bookmark, challenge, pref and unsynced deletion is uploaded, and the whole cloud library is
 *   pulled (from seq 0).
 *
 * Every write goes through the RAW DAOs: nothing here is stamped, recorded or published.
 */
class FirstMerge(
    private val api: SyncApi,
    private val db: SyncDatabase,
    private val applier: RemoteApplier,
    private val shares: ShareTracker?,
    private val coverStore: CoverStore? = null,
    private val ebookFiles: EbookFiles? = null,
    /** Books and rows the server set aside (kept on this device, not synced). */
    private val onSetAside: (Int) -> Unit = {},
) {
    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)

    /** (books done, books in all) while combining the libraries; null otherwise. */
    val progress: StateFlow<Pair<Int, Int>?> = _progress.asStateFlow()

    /** Does nothing when [owner]'s merge already happened on this device. Throws what the server threw; run again to resume. */
    suspend fun run(owner: String) {
        if (db.state.get(mergedKey(owner)) != null) return
        try {
            combine(owner)
        } finally {
            _progress.value = null
        }
    }

    private suspend fun combine(owner: String) {
        val live = db.books.getAllAny().filter { !it.deleted }
        if (live.isEmpty()) {
            db.state.set(mergedKey(owner), "1")
            return
        }
        val done = loadDone(owner)
        val total = live.size
        var count = live.count { it.uuid in done }
        _progress.value = count to total
        for (batch in live.filter { it.uuid !in done }.chunked(MERGE_BATCH)) {
            mergeBatch(owner, batch, done)
            count += batch.size
            _progress.value = count to total
        }
        queueTheRest()
        pushQueue(api, db, applier, onSetAside)
        pullFrom(api, db, applier, 0L)
        db.state.set(mergedKey(owner), "1")
    }

    // ---- books ----

    /** What to do once a batch has committed: files and share state live outside the transaction. */
    private class AfterCommit {
        val photos = mutableListOf<String>()
        val fileMoves = mutableListOf<Triple<Long, Long, EbookFormat>>()
        val fileDeletes = mutableListOf<Pair<Long, EbookFormat>>()
        val shareMoves = mutableListOf<Pair<Long, Long>>()
    }

    private suspend fun mergeBatch(owner: String, batch: List<BookEntity>, done: MutableSet<String>) {
        // Current rows: edited, or combined into another book, since the list was read.
        val books = batch.mapNotNull { db.books.getById(it.id) }
        if (books.isEmpty()) return
        val sent = books.associate { it.uuid to it.toRow(coverStore) }
        val result = api.mergeLibrary(sent.values.toList())
        val after = AfterCommit()
        applier.transaction {
            val children = Children()
            for (book in books) {
                val current = db.books.getById(book.id) ?: continue
                val cloudId = result.ids[current.uuid]
                done += if (cloudId == null || cloudId == current.uuid) {
                    current.uuid
                } else {
                    adopt(current, cloudId, sent[current.uuid], children, after)
                    cloudId
                }
            }
            db.state.set(mergeDoneKey(owner), JsonArray(done.map { JsonPrimitive(it) }).toString())
        }
        afterCommit(after)
        onSetAside(result.setAside.size)
    }

    /** [book] is the cloud's book [cloudId]: it takes that id, or joins the local book that already has it. */
    private suspend fun adopt(book: BookEntity, cloudId: String, sent: SyncRow?, children: Children, after: AfterCommit) {
        val holder = db.books.getByUuid(cloudId)
        if (holder != null && !holder.deleted) {
            combine(extra = book, kept = holder, children, after)
            return
        }
        // A deletion of the same cloud book made here earlier (never uploaded): the live copy wins.
        if (holder != null) removeForGood(holder, children, after)
        val adopted = book.copy(uuid = cloudId)
        db.books.update(adopted)
        children.rebind(book.id, book.id, cloudId)
        after.shareMoves += book.id to book.id
        // Edited since it was sent: the edit still has to reach the cloud.
        if (sent == null || sent.copy(id = cloudId) != adopted.toRow(coverStore)) enqueue(SyncTable.BOOKS, cloudId)
    }

    /** [extra] and [kept] are the same book: one row stays, with the newest value of each field. */
    private suspend fun combine(extra: BookEntity, kept: BookEntity, children: Children, after: AfterCommit) {
        val keptRow = kept.toRow(coverStore)
        val merged = mergeFields(keptRow, extra.toRow(coverStore).copy(id = kept.uuid))
        // A photo travels as a marker: keep the file of the side whose cover won, drop the other one.
        val extraCover = merged.fields["cover_url"] != keptRow.fields["cover_url"] ||
            merged.times["cover_url"] != keptRow.times["cover_url"]
        val cover = if (extraCover) extra.coverUrl else kept.coverUrl
        val dropped = if (extraCover) kept.coverUrl else extra.coverUrl
        if (dropped != cover && isLocalPhoto(dropped)) after.photos += dropped!!

        children.rebind(extra.id, kept.id, kept.uuid)
        children.moveEbook(extra.id, kept, after)
        // The extra row goes first: it may hold the ISBN the merged row takes.
        db.books.delete(extra.id)
        db.books.update(merged.toBookEntity(kept, coverStore).copy(coverUrl = cover))
        after.shareMoves += extra.id to kept.id
        enqueue(SyncTable.BOOKS, kept.uuid)
    }

    /** A local tombstone in the way of a live book: it goes, with its rows. */
    private suspend fun removeForGood(book: BookEntity, children: Children, after: AfterCommit) {
        children.drop(book.id, after)
        db.books.delete(book.id)
        if (isLocalPhoto(book.coverUrl)) after.photos += book.coverUrl!!
    }

    private fun isLocalPhoto(ref: String?): Boolean =
        if (coverStore != null) coverStore.isLocal(ref) else ref != null && ref.startsWith(CoverStore.PREFIX)

    /**
     * The quotes, bookmarks and ebooks of the batch, read once, by local book id. A child's row names its book by uuid
     * (`book_id`; an ebook's id), so moving it or renaming its book changes that field; its time is moved past every
     * other time of the row so the change wins over a copy the cloud may already hold.
     */
    private inner class Children {
        private val quotes: MutableMap<Long, MutableList<QuoteEntity>> = mutableMapOf()
        private val bookmarks: MutableMap<Long, MutableList<BookmarkEntity>> = mutableMapOf()
        private val ebooks: MutableMap<Long, EbookEntity> = mutableMapOf()
        private var loaded = false

        private suspend fun load() {
            if (loaded) return
            db.quotes.getAllAny().groupByTo(quotes) { it.bookId }
            db.bookmarks.getAllAny().groupByTo(bookmarks) { it.bookId }
            db.ebooks.getAllAny().associateByTo(ebooks) { it.bookId }
            loaded = true
        }

        /** The children of book [from] now belong to book [to], known as [toUuid]. */
        suspend fun rebind(from: Long, to: Long, toUuid: String) {
            load()
            val q = quotes.remove(from).orEmpty().map { it.copy(bookId = to, fieldTimes = bumped(it.fieldTimes)) }
            q.forEach { db.quotes.update(it) }
            if (q.isNotEmpty()) quotes.getOrPut(to) { mutableListOf() } += q
            val m = bookmarks.remove(from).orEmpty().map { it.copy(bookId = to, fieldTimes = bumped(it.fieldTimes)) }
            m.forEach { db.bookmarks.update(it) }
            if (m.isNotEmpty()) bookmarks.getOrPut(to) { mutableListOf() } += m
            if (from == to) {
                ebooks[to]?.takeIf { it.uuid != toUuid }?.copy(uuid = toUuid)?.let {
                    db.ebooks.upsert(it)
                    ebooks[to] = it
                }
            }
        }

        /** [from]'s ebook goes to [kept], unless [kept] has its own: then [from]'s record and file go. */
        suspend fun moveEbook(from: Long, kept: BookEntity, after: AfterCommit) {
            load()
            val ebook = ebooks.remove(from) ?: return
            db.ebooks.delete(from)
            if (ebooks[kept.id] == null) {
                val moved = ebook.copy(bookId = kept.id, uuid = kept.uuid)
                db.ebooks.upsert(moved)
                ebooks[kept.id] = moved
                after.fileMoves += Triple(from, kept.id, ebook.format)
            } else {
                after.fileDeletes += from to ebook.format
            }
        }

        suspend fun drop(book: Long, after: AfterCommit) {
            load()
            quotes.remove(book)
            bookmarks.remove(book)
            ebooks.remove(book)?.let { after.fileDeletes += book to it.format }
            db.quotes.deleteForBook(book)
            db.bookmarks.deleteForBook(book)
            db.ebooks.delete(book)
        }
    }

    private suspend fun afterCommit(after: AfterCommit) {
        after.photos.forEach { coverStore?.delete(it) }
        after.fileDeletes.forEach { (book, format) -> ebookFiles?.delete(book, format) }
        after.fileMoves.forEach { (from, to, format) -> ebookFiles?.move(from, to, format) }
        val tracker = shares ?: return
        for ((from, to) in after.shareMoves) {
            try {
                tracker.moveShare(from, to, db.books.getById(to)?.toBook())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The share state is best effort: the library is already merged.
            }
        }
    }

    // ---- everything else ----

    /**
     * Queues what isn't a live book: quotes, bookmarks, ebooks, challenges, prefs and deletions not uploaded yet (the
     * queue was cleared at sign-out). An ebook's uuid is its book's, set again here in case it drifted.
     */
    private suspend fun queueTheRest() = applier.transaction {
        val books = db.books.getAllAny()
        val uuidOf = books.associate { it.id to it.uuid }
        books.filter { it.deleted }.forEach { enqueue(SyncTable.BOOKS, it.uuid) }
        db.quotes.getAllAny().forEach { enqueue(SyncTable.QUOTES, it.uuid) }
        db.bookmarks.getAllAny().forEach { enqueue(SyncTable.BOOKMARKS, it.uuid) }
        for (ebook in db.ebooks.getAllAny()) {
            val uuid = uuidOf[ebook.bookId] ?: continue
            if (ebook.uuid != uuid) db.ebooks.upsert(ebook.copy(uuid = uuid))
            enqueue(SyncTable.EBOOKS, uuid)
        }
        db.challenges.getAllAny().forEach { enqueue(SyncTable.CHALLENGES, it.year.toString()) }
        val prefs = db.prefs ?: return@transaction
        for (key in prefs.keys) {
            if (db.state.get(prefTimeKey(key)) == null) {
                // Its time was cleared at sign-out: the oldest possible, so a value the cloud holds wins.
                if (prefs.get(key) == null) continue
                db.state.set(prefTimeKey(key), "0")
            }
            enqueue(SyncTable.PREFS, key)
        }
    }

    private suspend fun enqueue(table: SyncTable, id: String) = db.queue.enqueue(table.wire, id, 0)

    private suspend fun loadDone(owner: String): MutableSet<String> {
        val json = db.state.get(mergeDoneKey(owner)) ?: return mutableSetOf()
        return try {
            (Json.parseToJsonElement(json) as? JsonArray)?.mapNotNullTo(mutableSetOf()) { (it as? JsonPrimitive)?.contentOrNull }
                ?: mutableSetOf()
        } catch (_: Exception) {
            mutableSetOf()
        }
    }
}

/** [fieldTimes] with `book_id` timed after every other field of the row. */
private fun bumped(fieldTimes: String): String {
    val times = parseTimes(fieldTimes)
    return timesJson(times + ("book_id" to (times.values.maxOrNull() ?: 0L) + 1))
}
