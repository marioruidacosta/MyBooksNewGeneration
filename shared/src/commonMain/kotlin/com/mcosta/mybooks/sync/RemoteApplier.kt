package com.mcosta.mybooks.sync

import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.db.BookDao
import com.mcosta.mybooks.data.db.BookEntity
import com.mcosta.mybooks.data.db.BookmarkDao
import com.mcosta.mybooks.data.db.ChallengeDao
import com.mcosta.mybooks.data.db.EbookDao
import com.mcosta.mybooks.data.db.QuoteDao
import com.mcosta.mybooks.data.db.ShareStateDao
import com.mcosta.mybooks.data.db.SyncQueueDao
import com.mcosta.mybooks.data.db.SyncStateDao
import com.mcosta.mybooks.data.db.toBook
import com.mcosta.mybooks.share.ShareTracker
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The account-wide preferences that sync (pinned tags, "Not interested"): read for upload, written when pulled. */
interface SyncedPrefs {
    /** Every key that syncs: the first sign-in merge uploads them all. */
    val keys: Collection<String> get() = emptyList()

    fun get(key: String): String?

    /** A value pulled from another device; null removes it. Must not record a change (it would be pushed back). */
    fun set(key: String, value: String?)
}

/**
 * What the sync engine reads and writes. The DAOs are the RAW ones: pulled rows written through them are neither stamped
 * nor queued. [prefs] is null where preferences aren't kept (pref rows are then skipped).
 */
class SyncDatabase(
    val books: BookDao,
    val quotes: QuoteDao,
    val ebooks: EbookDao,
    val bookmarks: BookmarkDao,
    val challenges: ChallengeDao,
    val queue: SyncQueueDao,
    val state: SyncStateDao,
    val prefs: SyncedPrefs? = null,
    /** Erased with the library on "Remove" at sign-out; null where sharing isn't kept. */
    val shareState: ShareStateDao? = null,
)

/** `sync_state` key of the child rows waiting for their book (a JSON array of rows). */
internal const val ORPHANS_KEY = "orphans"

/** `sync_state` key holding a preference's last-change time. */
internal fun prefTimeKey(key: String) = "prefs/$key"

/**
 * Writes pulled rows into the local database, per field with the server's rule ([mergeFields]).
 * - Books first, then the rows that hang off a book. A child whose book isn't here yet waits in `sync_state["orphans"]`
 *   and is tried again on every [apply]; one whose book arrives deleted is dropped.
 * - A row the server holds as deleted is removed for good here (a book with its quotes, bookmarks, ebook record and
 *   cover photo). A local deletion the server doesn't have yet stays as a tombstone, to be pushed.
 * - A pulled book is never published: it is passed to [ShareTracker.syncedFromElsewhere], not `changed`.
 */
class RemoteApplier(
    private val db: SyncDatabase,
    private val recorder: SyncRecorder,
    private val coverStore: CoverStore? = null,
    private val shares: ShareTracker? = null,
) {
    /**
     * Applies [rows] in one transaction; [alsoInTransaction] runs inside it, so the engine's cursor is stored with the
     * page it belongs to. Cover photos are deleted and the share state told after the commit.
     */
    suspend fun apply(rows: List<SyncRow>, alsoInTransaction: suspend () -> Unit = {}) {
        val pulledBooks = mutableListOf<BookEntity>()
        val photos = mutableListOf<String>()
        recorder.transaction {
            val gone = mutableSetOf<String>()
            for (row in rows.filter { it.table == SyncTable.BOOKS }) applyBook(row, gone, pulledBooks, photos)
            val before = loadOrphans()
            val waiting = mergeDuplicates(before + rows.filter { it.table != SyncTable.BOOKS })
                .filterNot { applyOther(it) }
                .filterNot { it.isDeleted() || it.bookUuid() in gone }
            if (before.isNotEmpty() || waiting.isNotEmpty()) saveOrphans(waiting)
            alsoInTransaction()
        }
        photos.forEach { coverStore?.delete(it) }
        val tracker = shares ?: return
        for (book in pulledBooks) {
            try {
                tracker.syncedFromElsewhere(book.toBook())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The share state is best effort: the library is already up to date.
            }
        }
    }

    /** Runs [block] in one write transaction, the one [apply] uses. */
    internal suspend fun <T> transaction(block: suspend () -> T): T = recorder.transaction(block)

    /** The local row as it would be pushed, or null when it isn't on this device (any more). */
    suspend fun localRow(table: SyncTable, id: String): SyncRow? = when (table) {
        SyncTable.BOOKS -> db.books.getByUuid(id)?.toRow(coverStore)
        SyncTable.QUOTES -> db.quotes.getByUuid(id)?.toRow(::bookUuid)
        SyncTable.BOOKMARKS -> db.bookmarks.getByUuid(id)?.toRow(::bookUuid)
        SyncTable.EBOOKS -> db.ebooks.getByUuid(id)?.toRow(::bookUuid)
        SyncTable.CHALLENGES -> id.toIntOrNull()?.let { db.challenges.getByYearAny(it) }?.toRow()
        SyncTable.PREFS -> {
            val prefs = db.prefs
            val time = db.state.get(prefTimeKey(id))?.toLongOrNull()
            if (prefs == null || time == null) null else prefRow(id, prefs.get(id), time)
        }
    }

    // ---- books ----

    private suspend fun applyBook(remote: SyncRow, gone: MutableSet<String>, pulled: MutableList<BookEntity>, photos: MutableList<String>) {
        val local = db.books.getByUuid(remote.id)
        val localRow = local?.toRow(coverStore)
        val merged = mergeFields(localRow, remote)
        if (merged.isDeleted() && remote.isDeleted()) {
            gone += remote.id
            if (local != null) {
                db.quotes.deleteForBook(local.id)
                db.bookmarks.deleteForBook(local.id)
                db.ebooks.delete(local.id)
                db.books.delete(local.id)
                local.coverUrl?.takeIf { coverStore?.isLocal(it) == true }?.let { photos += it }
            }
            return
        }
        if (localRow != null && merged.sameAs(localRow)) return
        var book = merged.toBookEntity(local, coverStore)
        // A photo travels as a marker; while the local one wins, keep its own file name.
        if (local != null && merged.times["cover_url"] == localRow?.times?.get("cover_url")) book = book.copy(coverUrl = local.coverUrl)
        book = withFreeIsbn(book)
        if (local == null) book = book.copy(id = db.books.insert(book)) else db.books.update(book)
        if (!book.deleted) pulled += book
    }

    /**
     * The ISBN is unique on the device. A deleted row holding it gives it up (its deletion still syncs); a live one
     * (the same book added on two devices) keeps it, and the pulled copy is stored here without it.
     */
    private suspend fun withFreeIsbn(book: BookEntity): BookEntity {
        val isbn = book.isbn ?: return book
        val holder = db.books.findByIsbnAny(isbn)?.takeIf { it.uuid != book.uuid } ?: return book
        if (!holder.deleted) return book.copy(isbn = null)
        db.books.update(holder.copy(isbn = null))
        return book
    }

    // ---- everything else ----

    /** False when the row's book isn't on this device: it then waits as an orphan. */
    private suspend fun applyOther(remote: SyncRow): Boolean = when (remote.table) {
        SyncTable.BOOKS -> true
        SyncTable.QUOTES -> {
            val local = db.quotes.getByUuid(remote.id)
            applyChild(local?.toRow(::bookUuid), remote, remove = { local?.let { db.quotes.delete(it.id) } }) { merged ->
                val entity = merged.toQuoteEntity(local, ::bookIdOf) ?: return@applyChild
                if (local == null) db.quotes.insert(entity) else db.quotes.update(entity)
            }
        }
        SyncTable.BOOKMARKS -> {
            val local = db.bookmarks.getByUuid(remote.id)
            applyChild(local?.toRow(::bookUuid), remote, remove = { local?.let { db.bookmarks.delete(it.id) } }) { merged ->
                val entity = merged.toBookmarkEntity(local, ::bookIdOf) ?: return@applyChild
                if (local == null) db.bookmarks.insert(entity) else db.bookmarks.update(entity)
            }
        }
        SyncTable.EBOOKS -> {
            val local = db.ebooks.getByUuid(remote.id)
            applyChild(local?.toRow(::bookUuid), remote, remove = { local?.let { db.ebooks.delete(it.bookId) } }) { merged ->
                merged.toEbookEntity(local, ::bookIdOf)?.let { db.ebooks.upsert(it) }
            }
        }
        SyncTable.CHALLENGES -> {
            val year = remote.id.toIntOrNull()
            val local = year?.let { db.challenges.getByYearAny(it) }
            val merged = mergeFields(local?.toRow(), remote)
            when {
                year == null -> Unit
                merged.isDeleted() && remote.isDeleted() -> if (local != null) db.challenges.delete(year)
                local != null && merged.sameAs(local.toRow()) -> Unit
                else -> merged.toChallengeEntity()?.let { db.challenges.upsert(it) }
            }
            true
        }
        SyncTable.PREFS -> {
            applyPref(remote)
            true
        }
    }

    /** Merges a quote, bookmark or ebook row; false when its book isn't here (yet). */
    private suspend fun applyChild(
        localRow: SyncRow?,
        remote: SyncRow,
        remove: suspend () -> Unit,
        write: suspend (SyncRow) -> Unit,
    ): Boolean {
        val merged = mergeFields(localRow, remote)
        if (merged.isDeleted() && remote.isDeleted()) {
            remove()
            return true
        }
        if (localRow != null && merged.sameAs(localRow)) return true
        val book = merged.bookUuid() ?: return true
        if (bookIdOf(book) == null) return false
        write(merged)
        return true
    }

    private suspend fun applyPref(remote: SyncRow) {
        val prefs = db.prefs ?: return
        val remoteTime = remote.times.values.maxOrNull() ?: return
        val localTime = db.state.get(prefTimeKey(remote.id))?.toLongOrNull() ?: -1L
        if (remoteTime <= localTime) return
        prefs.set(remote.id, remote.prefValue())
        db.state.set(prefTimeKey(remote.id), remoteTime.toString())
    }

    // ---- helpers ----

    private suspend fun bookIdOf(uuid: String): Long? = db.books.getByUuid(uuid)?.id

    /** A local book's uuid, deleted books included (a deleted quote of a deleted book still syncs). */
    private suspend fun bookUuid(id: Long): String? =
        db.books.getById(id)?.uuid ?: db.books.getAllAny().firstOrNull { it.id == id }?.uuid

    private suspend fun loadOrphans(): List<SyncRow> {
        val json = db.state.get(ORPHANS_KEY) ?: return emptyList()
        return try {
            (Json.parseToJsonElement(json) as? JsonArray)?.mapNotNull { (it as? JsonObject)?.toSyncRow() }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun saveOrphans(rows: List<SyncRow>) =
        db.state.set(ORPHANS_KEY, JsonArray(rows.map { it.toJson() }).toString())
}

private fun SyncRow.isDeleted(): Boolean = (fields["deleted"] as? JsonPrimitive)?.booleanOrNull == true

private fun SyncRow.sameAs(other: SyncRow): Boolean = fields == other.fields && times == other.times

/** The uuid of the book a child row belongs to (an ebook's id is its book's uuid); null for other rows. */
private fun SyncRow.bookUuid(): String? = when (table) {
    SyncTable.QUOTES, SyncTable.BOOKMARKS -> (fields["book_id"] as? JsonPrimitive)?.contentOrNull
    SyncTable.EBOOKS -> id
    else -> null
}

/** Several versions of one row (an older orphan and a newer pull) become one, newest field wins. */
private fun mergeDuplicates(rows: List<SyncRow>): List<SyncRow> {
    val byKey = LinkedHashMap<Pair<SyncTable, String>, SyncRow>()
    for (row in rows) byKey[row.table to row.id] = mergeFields(byKey[row.table to row.id], row)
    return byKey.values.toList()
}

private fun SyncRow.toJson(): JsonObject = buildJsonObject {
    put("table", table.wire)
    put("id", id)
    put("fields", JsonObject(fields))
    put("times", JsonObject(times.mapValues { JsonPrimitive(it.value) }))
    put("seq", seq)
}

private fun JsonObject.toSyncRow(): SyncRow? {
    val wire = (this["table"] as? JsonPrimitive)?.contentOrNull
    val table = SyncTable.entries.firstOrNull { it.wire == wire } ?: return null
    val id = (this["id"] as? JsonPrimitive)?.contentOrNull ?: return null
    val fields: Map<String, JsonElement> = (this["fields"] as? JsonObject) ?: return null
    val times = (this["times"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { k to it } }?.toMap()
        ?: return null
    return SyncRow(table, id, fields, times, (this["seq"] as? JsonPrimitive)?.longOrNull ?: 0)
}
