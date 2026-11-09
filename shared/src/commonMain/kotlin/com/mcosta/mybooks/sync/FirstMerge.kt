package com.mcosta.mybooks.sync

import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.db.BookEntity
import com.mcosta.mybooks.data.db.BookmarkEntity
import com.mcosta.mybooks.data.db.EbookEntity
import com.mcosta.mybooks.data.db.QuoteEntity
import com.mcosta.mybooks.data.db.toBook
import com.mcosta.mybooks.share.ShareTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** `merge_library` takes at most this many books per call. */
internal const val MERGE_BATCH = 100

/** `sync_state` key set once [owner]'s first sign-in merge is over on this device. */
internal fun mergedKey(owner: String) = "merged:$owner"

/** `sync_state` key of the books already merged (their uuids, a JSON array): what lets a merge resume. */
internal fun mergeDoneKey(owner: String) = "merge-done:$owner"

/** `sync_state` key of the share state still to move: `[[from, to], ...]`, local book ids, in order. */
internal fun mergeSharesKey(owner: String) = "merge-shares:$owner"

/**
 * The first sign-in on a device, once per account: joins the device's library to the cloud one.
 * - The live books go to `merge_library` [MERGE_BATCH] at a time. The server matches each to a cloud book (or keeps it
 *   as a new one) and answers with its cloud id, which the local row takes. Two local books matched to the same cloud
 *   book become one here too (see [combine]). After each batch the merged uuids are stored, so an interrupted merge
 *   resumes there.
 * - Then everything else is uploaded (quotes, ebooks, bookmarks, challenges, prefs, deletions never synced) and the
 *   whole cloud library is pulled from seq 0. Only then, with the rows holding the merged values, does the share state
 *   move to the books' new ids. The moves are recorded with each batch, so a crash before they run doesn't lose them.
 * - A device with no live books does the same, minus the batches.
 *
 * Every write goes through the RAW DAOs: nothing here is stamped, recorded or published. Ebook files are never deleted.
 */
class FirstMerge(
    private val api: SyncApi,
    private val db: SyncDatabase,
    private val applier: RemoteApplier,
    private val shares: ShareTracker?,
    private val coverStore: CoverStore? = null,
    /** Books and rows the server set aside (kept on this device, not synced). */
    private val onSetAside: (Int) -> Unit = {},
) {
    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)

    /** (books done, books in all) while combining the libraries; null otherwise. */
    val progress: StateFlow<Pair<Int, Int>?> = _progress.asStateFlow()

    private val _ebooksLeftOnDisk = MutableStateFlow(0)

    /**
     * Ebook files of combined copies that lost to the other copy's ebook (opened less recently). No book points at them
     * any more, but they stay on disk (`<old book id>.<ext>`): they may exist only on this phone.
     */
    val ebooksLeftOnDisk: StateFlow<Int> = _ebooksLeftOnDisk.asStateFlow()

    /** Does nothing when [owner]'s merge already happened on this device. Throws what the server threw; run again to resume. */
    suspend fun run(owner: String) {
        if (db.state.get(mergedKey(owner)) != null) return
        try {
            combineLibraries(owner)
        } finally {
            _progress.value = null
        }
    }

    private suspend fun combineLibraries(owner: String) {
        val live = db.books.getAllAny().filter { !it.deleted }
        if (live.isNotEmpty()) {
            val done = loadDone(owner)
            val total = live.size
            var count = live.count { it.uuid in done }
            _progress.value = count to total
            for (batch in live.filter { it.uuid !in done }.chunked(MERGE_BATCH)) {
                mergeBatch(owner, batch, done)
                count += batch.size
                _progress.value = count to total
            }
        }
        queueTheRest()
        pushQueue(api, db, applier, onSetAside)
        // From 0, whatever cursor another account left behind.
        pullFrom(api, db, applier, 0L)
        moveShares(owner)
    }

    // ---- books ----

    private suspend fun mergeBatch(owner: String, batch: List<BookEntity>, done: MutableSet<String>) {
        // Current rows: edited, or combined into another book, since the list was read.
        val books = batch.mapNotNull { db.books.getById(it.id) }
        if (books.isEmpty()) return
        val sent = books.associate { it.uuid to it.toRow(coverStore) }
        val result = api.mergeLibrary(sent.values.toList())
        val photos = mutableListOf<String>()
        applier.transaction {
            val children = Children()
            val moves = mutableListOf<Pair<Long, Long>>()
            for (book in books) {
                val current = db.books.getById(book.id) ?: continue
                val cloudId = result.ids[current.uuid]
                if (cloudId != null && cloudId != current.uuid) adopt(current, cloudId, sent[current.uuid], children, moves, photos)
                done += cloudId ?: current.uuid
            }
            db.state.set(mergeDoneKey(owner), JsonArray(done.map { JsonPrimitive(it) }).toString())
            if (moves.isNotEmpty()) saveMoves(owner, loadMoves(owner) + moves)
        }
        // Unused photos only: losing one to a crash here leaves a stray file, nothing more.
        photos.forEach { coverStore?.delete(it) }
        onSetAside(result.setAside.size)
    }

    /** [book] is the cloud's book [cloudId]: it takes that id, or is combined with the local book that already has it. */
    private suspend fun adopt(
        book: BookEntity,
        cloudId: String,
        sent: SyncRow?,
        children: Children,
        moves: MutableList<Pair<Long, Long>>,
        photos: MutableList<String>,
    ) {
        val holder = db.books.getByUuid(cloudId)
        if (holder != null && !holder.deleted) {
            combine(extra = book, holder = holder, children, moves, photos)
            return
        }
        // A deletion of the same cloud book made here earlier (never uploaded): the live copy wins.
        if (holder != null) removeForGood(holder, children, photos)
        val adopted = book.copy(uuid = cloudId)
        db.books.update(adopted)
        children.rebind(book.id, book.id)
        children.renameEbook(book.id, cloudId)
        moves += book.id to book.id
        // Edited since it was sent: the edit still has to reach the cloud (its old queue entry no longer matches).
        if (sent == null || sent.copy(id = cloudId) != adopted.toRow(coverStore)) enqueue(SyncTable.BOOKS, cloudId)
    }

    /**
     * [extra] and [holder] (which has the cloud id) are the same book. One row stays, as the cloud's book:
     * - Each field takes the newest value ([mergeFields]). Notes and reviews that differ are both kept, newest first.
     * - The row that stays is the one whose ebook was opened last (or the only one with an ebook; else [holder]), so
     *   that ebook's file needs no moving. The other ebook's record goes; its file stays on disk.
     * - Quotes and bookmarks move to the row that stays. Its share state moves after the final pull.
     */
    private suspend fun combine(
        extra: BookEntity,
        holder: BookEntity,
        children: Children,
        moves: MutableList<Pair<Long, Long>>,
        photos: MutableList<String>,
    ) {
        val holderRow = holder.toRow(coverStore)
        val extraRow = extra.toRow(coverStore).copy(id = holder.uuid)
        val merged = keepBothTexts(mergeFields(holderRow, extraRow), holderRow, extraRow, "notes", "review")
        // A photo travels as a marker: keep the file of the side whose cover won, drop the other one.
        val extraCover = merged.fields["cover_url"] != holderRow.fields["cover_url"] ||
            merged.times["cover_url"] != holderRow.times["cover_url"]
        val cover = if (extraCover) extra.coverUrl else holder.coverUrl
        val dropped = if (extraCover) holder.coverUrl else extra.coverUrl
        if (dropped != cover && isLocalPhoto(dropped)) photos += dropped!!

        val (stays, goes) = if (children.ebookWins(extra.id, holder.id)) extra to holder else holder to extra
        children.rebind(stays.id, stays.id)
        children.rebind(goes.id, stays.id)
        children.renameEbook(stays.id, holder.uuid)
        if (children.dropEbook(goes.id)) _ebooksLeftOnDisk.update { it + 1 }
        // The row that goes first: it may hold the ISBN, and the uuid, that the row that stays takes.
        db.books.delete(goes.id)
        db.books.update(merged.toBookEntity(stays, coverStore).copy(coverUrl = cover))
        moves += stays.id to stays.id
        moves += goes.id to stays.id
        enqueue(SyncTable.BOOKS, holder.uuid)
    }

    /** A local tombstone in the way of a live book: it goes, with its rows (an ebook file stays on disk). */
    private suspend fun removeForGood(book: BookEntity, children: Children, photos: MutableList<String>) {
        children.drop(book.id)
        db.books.delete(book.id)
        if (isLocalPhoto(book.coverUrl)) photos += book.coverUrl!!
    }

    private fun isLocalPhoto(ref: String?): Boolean =
        if (coverStore != null) coverStore.isLocal(ref) else ref != null && ref.startsWith(CoverStore.PREFIX)

    /**
     * The quotes, bookmarks and ebooks of the batch, read once, by local book id. A child's row names its book by uuid
     * (`book_id`; an ebook's id), so moving it or renaming its book changes that field. Its time is moved past every
     * other time of the row, so the change wins over a copy the cloud may already hold.
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

        /** The quotes and bookmarks of book [from] now belong to book [to] (the same book when only its uuid changed). */
        suspend fun rebind(from: Long, to: Long) {
            load()
            val q = quotes.remove(from).orEmpty().map { it.copy(bookId = to, fieldTimes = bumped(it.fieldTimes)) }
            q.forEach { db.quotes.update(it) }
            if (q.isNotEmpty()) quotes.getOrPut(to) { mutableListOf() } += q
            val m = bookmarks.remove(from).orEmpty().map { it.copy(bookId = to, fieldTimes = bumped(it.fieldTimes)) }
            m.forEach { db.bookmarks.update(it) }
            if (m.isNotEmpty()) bookmarks.getOrPut(to) { mutableListOf() } += m
        }

        /** The ebook of [book] follows the book's new [uuid] (an ebook's uuid is its book's). */
        suspend fun renameEbook(book: Long, uuid: String) {
            load()
            ebooks[book]?.takeIf { it.uuid != uuid }?.copy(uuid = uuid)?.let {
                db.ebooks.upsert(it)
                ebooks[book] = it
            }
        }

        /** True when [a]'s ebook beats [b]'s: opened last (then: with a reading position), or [b] has none. */
        suspend fun ebookWins(a: Long, b: Long): Boolean {
            load()
            val ea = ebooks[a]?.takeIf { !it.deleted } ?: return false
            val eb = ebooks[b]?.takeIf { !it.deleted } ?: return true
            return compareValuesBy(ea, eb, { it.lastOpened ?: Long.MIN_VALUE }, { it.position != null }) > 0
        }

        /** Removes [book]'s ebook record; true when it was a live one (its file is left on disk). */
        suspend fun dropEbook(book: Long): Boolean {
            load()
            val ebook = ebooks.remove(book) ?: return false
            db.ebooks.delete(book)
            return !ebook.deleted
        }

        suspend fun drop(book: Long) {
            load()
            quotes.remove(book)
            bookmarks.remove(book)
            ebooks.remove(book)
            db.quotes.deleteForBook(book)
            db.bookmarks.deleteForBook(book)
            db.ebooks.delete(book)
        }
    }

    // ---- share state ----

    /**
     * Runs the recorded share moves, now that the rows hold the merged values (the republished copy carries them). A
     * move already done is a no-op, so replaying them all after a crash is safe. The merge is marked done with them
     * cleared, in one transaction.
     */
    private suspend fun moveShares(owner: String) {
        val tracker = shares
        if (tracker != null) {
            for ((from, to) in loadMoves(owner)) tracker.moveShare(from, to, db.books.getById(to)?.toBook())
        }
        applier.transaction {
            saveMoves(owner, emptyList())
            db.state.set(mergedKey(owner), "1")
        }
    }

    private suspend fun loadMoves(owner: String): List<Pair<Long, Long>> =
        parseArray(db.state.get(mergeSharesKey(owner))).mapNotNull { pair ->
            val ids = (pair as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
            if (ids?.size == 2) ids[0] to ids[1] else null
        }

    private suspend fun saveMoves(owner: String, moves: List<Pair<Long, Long>>) = db.state.set(
        mergeSharesKey(owner),
        JsonArray(moves.map { (from, to) -> JsonArray(listOf(JsonPrimitive(from), JsonPrimitive(to))) }).toString(),
    )

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

    private suspend fun loadDone(owner: String): MutableSet<String> =
        parseArray(db.state.get(mergeDoneKey(owner))).mapNotNullTo(mutableSetOf()) { (it as? JsonPrimitive)?.contentOrNull }
}

private fun parseArray(json: String?): JsonArray = try {
    json?.let { Json.parseToJsonElement(it) as? JsonArray } ?: JsonArray(emptyList())
} catch (_: Exception) {
    JsonArray(emptyList())
}

/** [fieldTimes] with `book_id` timed after every other field of the row. */
private fun bumped(fieldTimes: String): String {
    val times = parseTimes(fieldTimes)
    return timesJson(times + ("book_id" to (times.values.maxOrNull() ?: 0L) + 1))
}

/**
 * Two copies of a book, each with its own text in [fields] (notes, review): both are kept, the newer first, a blank
 * line, then the older. The joined text is timed after both, so it wins everywhere.
 */
private fun keepBothTexts(merged: SyncRow, a: SyncRow, b: SyncRow, vararg fields: String): SyncRow {
    val f = merged.fields.toMutableMap()
    val t = merged.times.toMutableMap()
    for (field in fields) {
        val va = (a.fields[field] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val vb = (b.fields[field] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (va.isBlank() || vb.isBlank() || va == vb) continue
        val ta = a.times[field] ?: -1L
        val tb = b.times[field] ?: -1L
        f[field] = JsonPrimitive(if (tb > ta) "$vb\n\n$va" else "$va\n\n$vb")
        t[field] = maxOf(ta, tb) + 1
    }
    return merged.copy(fields = f, times = t)
}
