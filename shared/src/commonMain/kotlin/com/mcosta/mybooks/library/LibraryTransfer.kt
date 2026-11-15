package com.mcosta.mybooks.library

import com.mcosta.mybooks.data.authors.catalogTitleKey
import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.db.BookEntity
import com.mcosta.mybooks.data.db.BookmarkEntity
import com.mcosta.mybooks.data.db.EbookEntity
import com.mcosta.mybooks.data.db.QuoteEntity
import com.mcosta.mybooks.data.ebooks.EbookFiles
import com.mcosta.mybooks.domain.searchKey
import com.mcosta.mybooks.sync.SyncDatabase
import com.mcosta.mybooks.sync.SyncRecorder
import com.mcosta.mybooks.sync.SyncRow
import com.mcosta.mybooks.sync.SyncTable
import com.mcosta.mybooks.sync.SyncingChallengeDao
import com.mcosta.mybooks.sync.SyncingEbookDao
import com.mcosta.mybooks.sync.bumped
import com.mcosta.mybooks.sync.mergeFields
import com.mcosta.mybooks.sync.parseTimes
import com.mcosta.mybooks.sync.timesJson
import com.mcosta.mybooks.sync.toBookEntity
import com.mcosta.mybooks.sync.toRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okio.FileSystem
import okio.Path
import okio.use

/** `sync_state` key set in an account library once its owner answered "Add this phone's N books to your account?". */
internal const val ASKED_LOCAL_BOOKS_KEY = "asked-local-books"

/**
 * One library's tables and folders, as [LibraryTransfer] moves books between libraries. [db] holds the RAW DAOs (they
 * neither stamp nor queue); [recorder] runs the library's write transactions and queues rows where the library records
 * its changes (an account library; the no-account library never queues).
 */
class LibraryContents(
    val db: SyncDatabase,
    val recorder: SyncRecorder,
    val coverStore: CoverStore,
    val ebookFiles: EbookFiles,
    val fileSystem: FileSystem,
)

/**
 * Moves every live book of one library into another, with its quotes, bookmarks, ebook record, cover photo and ebook
 * file, and the goals. Never between two accounts' libraries: only the no-account library and one account's.
 *
 * - A book matches a book of the target by ISBN, or by title and first author when a side has no ISBN (the rule of
 *   [com.mcosta.mybooks.data.authors.findInLibrary]). A match is combined with [mergeFields] (the newest value of each
 *   field; where neither side has a time, a value beats an empty one, as `merge_library` does), its differing notes and
 *   review both kept as in the first sign-in merge, and the source's quotes and bookmarks move to it. Its ebook is the
 *   one opened last.
 * - Any other book is written to the target as it is: same uuid and field times, so the first sign-in merge or the next
 *   push weighs its fields fairly against the cloud's.
 * - Each row written to the target is queued where the target records (an account library that joined its cloud
 *   library); before that, the first sign-in merge uploads every live row.
 * - A goal for a year the target already has stays the target's.
 *
 * Book by book, the target's rows are written (one transaction), then its files, and only then are the source's rows
 * deleted (another transaction) and its files. A move that stops partway can be run again: a book already in the target
 * (by uuid) is not written twice, and a combined book combines to the same result.
 */
class LibraryTransfer(private val io: CoroutineDispatcher = Dispatchers.IO) {
    /** Moves [from]'s live books and goals into [to]; returns how many books were moved. */
    suspend fun move(from: LibraryContents, to: LibraryContents): Int {
        val books = from.db.books.getAllAny().filter { !it.deleted }
        if (books.isNotEmpty()) {
            val quotes = from.db.quotes.getAllAny().filter { !it.deleted }.groupBy { it.bookId }
            val bookmarks = from.db.bookmarks.getAllAny().filter { !it.deleted }.groupBy { it.bookId }
            val ebooks = from.db.ebooks.getAllAny().filter { !it.deleted }.associateBy { it.bookId }
            val titles = TitleIndex(to.db.books.getAllAny().filter { !it.deleted })
            for (book in books) {
                moveBook(book, Children(quotes[book.id].orEmpty(), bookmarks[book.id].orEmpty(), ebooks[book.id]), from, to, titles)
            }
        }
        moveGoals(from, to)
        return books.size
    }

    private class Children(val quotes: List<QuoteEntity>, val bookmarks: List<BookmarkEntity>, val ebook: EbookEntity?)

    /** Where a source book ended up: the target's row, and the target's own photo it no longer uses. */
    private class Placed(val book: BookEntity, val droppedPhoto: String?)

    private suspend fun moveBook(book: BookEntity, children: Children, from: LibraryContents, to: LibraryContents, titles: TitleIndex) {
        // The photo first, so the target's row never names a missing file; a file already there is left as it is.
        val photo = from.coverStore.fileFor(book.coverUrl)
        val photoCopied = photo != null &&
            withContext(io) { copyIfMissing(from.fileSystem, photo, to.fileSystem, to.coverStore.dir / photo.name) }

        val placed = to.recorder.transaction { place(book, children, from, to, titles) }

        if (photoCopied && placed.book.coverUrl != book.coverUrl) to.coverStore.delete(book.coverUrl)
        placed.droppedPhoto?.let(to.coverStore::delete)
        val ebook = children.ebook
        val ebookFileMoved = ebook != null && withContext(io) { moveEbookFile(book.id, ebook, placed.book.id, from, to) }

        from.recorder.transaction {
            from.db.quotes.deleteForBook(book.id)
            from.db.bookmarks.deleteForBook(book.id)
            from.db.ebooks.delete(book.id)
            from.db.books.delete(book.id)
        }
        from.coverStore.delete(book.coverUrl)
        if (ebook != null && ebookFileMoved) from.ebookFiles.delete(book.id, ebook.format)
    }

    /** In the target's transaction: writes [book] and its rows there. */
    private suspend fun place(book: BookEntity, children: Children, from: LibraryContents, to: LibraryContents, titles: TitleIndex): Placed {
        val now = to.recorder.now()
        val same = to.db.books.getByUuid(book.uuid)
        val placed = when {
            // Moved already, by a move that stopped before letting go of the source's row.
            same != null && !same.deleted -> Placed(same, null)
            same != null -> {
                // Deleted in the target (that deletion not uploaded yet): the live copy wins.
                releaseIsbn(book.isbn, same.id, to, now)
                val revived = book.copy(id = same.id, fieldTimes = timesJson(parseTimes(book.fieldTimes) + ("deleted" to now)))
                to.db.books.update(revived)
                to.recorder.record(SyncTable.BOOKS, revived.uuid)
                Placed(revived, null)
            }
            else -> {
                val match = (book.isbn?.let { to.db.books.findByIsbn(it) } ?: titles.find(book)?.let { to.db.books.getById(it.id) })
                if (match != null) {
                    combine(book, match, from, to, now).also { titles.replace(match, it.book) }
                } else {
                    releaseIsbn(book.isbn, null, to, now)
                    val id = to.db.books.insert(book.copy(id = 0))
                    to.recorder.record(SyncTable.BOOKS, book.uuid)
                    Placed(book.copy(id = id), null).also { titles.add(it.book) }
                }
            }
        }
        val target = placed.book
        val rebound = target.uuid != book.uuid
        for (quote in children.quotes) {
            val there = to.db.quotes.getByUuid(quote.uuid)
            if (there != null && !there.deleted) continue
            val times = movedTimes(quote.fieldTimes, rebound, revived = there != null, now)
            val moved = quote.copy(id = there?.id ?: 0, bookId = target.id, fieldTimes = times)
            if (there == null) to.db.quotes.insert(moved) else to.db.quotes.update(moved)
            to.recorder.record(SyncTable.QUOTES, quote.uuid)
        }
        for (bookmark in children.bookmarks) {
            val there = to.db.bookmarks.getByUuid(bookmark.uuid)
            if (there != null && !there.deleted) continue
            val times = movedTimes(bookmark.fieldTimes, rebound, revived = there != null, now)
            val moved = bookmark.copy(id = there?.id ?: 0, bookId = target.id, fieldTimes = times)
            if (there == null) to.db.bookmarks.insert(moved) else to.db.bookmarks.update(moved)
            to.recorder.record(SyncTable.BOOKMARKS, bookmark.uuid)
        }
        children.ebook?.let { placeEbook(it, target, to, now) }
        return placed
    }

    /**
     * [book] and the target's [held] are the same book: [held] stays (its id and uuid), with each field's newest value,
     * both notes and both reviews when they differ, and the photo of the side whose cover won.
     */
    private suspend fun combine(book: BookEntity, held: BookEntity, from: LibraryContents, to: LibraryContents, now: Long): Placed {
        val heldRow = held.toRow(to.coverStore)
        val bookRow = book.toRow(from.coverStore).copy(id = held.uuid)
        var merged = valuesOverEmpty(mergeFields(heldRow, bookRow), heldRow, bookRow)
        for (field in listOf("notes", "review")) merged = keepBothTexts(merged, heldRow, bookRow, field)
        // A photo travels as a marker: the side whose cover won keeps its file (copied over already).
        val bookCover = merged.fields["cover_url"] != heldRow.fields["cover_url"] || merged.times["cover_url"] != heldRow.times["cover_url"]
        val cover = if (bookCover) book.coverUrl else held.coverUrl
        val dropped = held.coverUrl.takeIf { bookCover && it != cover && to.coverStore.isLocal(it) }
        val next = merged.toBookEntity(held, to.coverStore).copy(coverUrl = cover)
        if (next != held) {
            releaseIsbn(next.isbn, held.id, to, now)
            to.db.books.update(next)
            to.recorder.record(SyncTable.BOOKS, held.uuid)
        }
        return Placed(next, dropped)
    }

    /** The source's ebook goes to [target] when the target has none, or when it was opened last; else the target's stays. */
    private suspend fun placeEbook(ebook: EbookEntity, target: BookEntity, to: LibraryContents, now: Long) {
        val theirs = to.db.ebooks.getAllAny().firstOrNull { it.bookId == target.id }
        when {
            theirs == null -> {
                to.db.ebooks.upsert(ebook.copy(bookId = target.id, uuid = target.uuid))
                to.recorder.record(SyncTable.EBOOKS, target.uuid)
            }
            theirs.deleted -> {
                val times = timesJson(parseTimes(ebook.fieldTimes) + ("deleted" to now))
                to.db.ebooks.upsert(ebook.copy(bookId = target.id, uuid = target.uuid, fieldTimes = times))
                to.recorder.record(SyncTable.EBOOKS, target.uuid)
            }
            // Through the wrapper: the fields that change are stamped now, so this ebook wins in the cloud too.
            opensLater(ebook, theirs) -> SyncingEbookDao(to.db.ebooks, to.db.books, to.recorder).upsert(ebook.copy(bookId = target.id))
        }
    }

    /** True when [a] was opened after [b] (then: has a reading position and [b] doesn't). A tie keeps [b]. */
    private fun opensLater(a: EbookEntity, b: EbookEntity): Boolean =
        compareValuesBy(a, b, { it.lastOpened ?: Long.MIN_VALUE }, { it.position != null }) > 0

    /**
     * The source's ebook file follows its book to the target's id, when the target's book now has an ebook of that format
     * whose file isn't there yet. True when the target holds the file afterwards (so the source's can go).
     */
    private suspend fun moveEbookFile(sourceId: Long, ebook: EbookEntity, targetId: Long, from: LibraryContents, to: LibraryContents): Boolean {
        val source = from.ebookFiles.path(sourceId, ebook.format)
        if (!from.fileSystem.exists(source)) return false
        val theirs = to.db.ebooks.get(targetId)
        if (theirs == null || theirs.format != ebook.format) return false
        val target = to.ebookFiles.path(targetId, ebook.format)
        copyIfMissing(from.fileSystem, source, to.fileSystem, target)
        return to.fileSystem.metadataOrNull(target)?.size == from.fileSystem.metadataOrNull(source)?.size
    }

    /** Goals the target doesn't have; the target's own goal for a year wins. */
    private suspend fun moveGoals(from: LibraryContents, to: LibraryContents) {
        for (goal in from.db.challenges.getAllAny().filter { !it.deleted }) {
            to.recorder.transaction {
                val theirs = to.db.challenges.getByYearAny(goal.year)
                when {
                    theirs == null -> {
                        to.db.challenges.upsert(goal)
                        to.recorder.record(SyncTable.CHALLENGES, goal.year.toString())
                    }
                    // Deleted there (not uploaded yet): this one comes back, stamped now so it wins.
                    theirs.deleted -> SyncingChallengeDao(to.db.challenges, to.recorder).upsert(goal)
                }
            }
            from.recorder.transaction { from.db.challenges.delete(goal.year) }
        }
    }

    /** A deleted target row (not [except]) still holding [isbn] in the unique index lets go of it, as an edit would. */
    private suspend fun releaseIsbn(isbn: String?, except: Long?, to: LibraryContents, now: Long) {
        val tomb = isbn?.let { to.db.books.findByIsbnAny(it) }?.takeIf { it.deleted && it.id != except } ?: return
        to.db.books.update(tomb.copy(isbn = null, fieldTimes = timesJson(parseTimes(tomb.fieldTimes) + ("isbn" to now))))
        to.recorder.record(SyncTable.BOOKS, tomb.uuid)
    }

    private fun movedTimes(fieldTimes: String, rebound: Boolean, revived: Boolean, now: Long): String {
        // A child moved to another book: its `book_id` changed, so that change wins over a copy the cloud may hold.
        val times = if (rebound) bumped(fieldTimes) else fieldTimes
        return if (revived) timesJson(parseTimes(times) + ("deleted" to now)) else times
    }

    private fun copyIfMissing(fromFs: FileSystem, source: Path, toFs: FileSystem, target: Path): Boolean {
        if (toFs.exists(target) || !fromFs.exists(source)) return false
        target.parent?.let(toFs::createDirectories)
        val partial = target.parent!! / "${target.name}.part"
        try {
            fromFs.source(source).use { input -> toFs.write(partial) { writeAll(input) } }
            toFs.atomicMove(partial, target)
        } finally {
            toFs.delete(partial, mustExist = false)
        }
        return true
    }
}

/** The target's live books by title and first author, as [com.mcosta.mybooks.data.authors.findInLibrary] matches them. */
private class TitleIndex(books: List<BookEntity>) {
    private val byKey = mutableMapOf<String, MutableList<BookEntity>>()

    init {
        books.forEach(::add)
    }

    private fun key(book: BookEntity) = catalogTitleKey(book.title) + "|" + searchKey(book.authors.substringBefore(',').trim())

    fun add(book: BookEntity) {
        byKey.getOrPut(key(book)) { mutableListOf() } += book
    }

    fun replace(old: BookEntity, new: BookEntity) {
        byKey[key(old)]?.removeAll { it.id == old.id }
        add(new)
    }

    /** Same title and first author, only where one of the two has no ISBN (two ISBNs are two books). */
    fun find(book: BookEntity): BookEntity? =
        byKey[key(book)]?.firstOrNull { book.isbn == null || it.isbn == null }
}

private fun SyncRow.text(field: String): String = (fields[field] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()

/**
 * Where neither side has a time for a field (rows from before sync), a value beats an empty one: `merge_library`'s
 * rule. The value taken is timed 1, so the cloud takes it over an empty, untimed one there too.
 */
private fun valuesOverEmpty(merged: SyncRow, held: SyncRow, incoming: SyncRow): SyncRow {
    val fields = merged.fields.toMutableMap()
    val times = merged.times.toMutableMap()
    for ((field, value) in incoming.fields) {
        if (field == "deleted" || (held.times[field] ?: 0L) > 0 || (incoming.times[field] ?: 0L) > 0) continue
        if (merged.text(field).isEmpty() && incoming.text(field).isNotEmpty()) {
            fields[field] = value
            times[field] = 1L
        }
    }
    return merged.copy(fields = fields, times = times)
}

/**
 * The first sign-in merge's twin rule for [field] (notes, review): two different texts are both kept, the newer first,
 * then a blank line, then the older, timed after both. A text that already holds the other (a move run again) stays.
 */
private fun keepBothTexts(merged: SyncRow, held: SyncRow, incoming: SyncRow, field: String): SyncRow {
    val mine = held.text(field)
    val theirs = incoming.text(field)
    if (mine.isBlank() || theirs.isBlank() || mine == theirs) return merged
    val tMine = held.times[field] ?: -1L
    val tTheirs = incoming.times[field] ?: -1L
    val text = when {
        theirs in mine -> mine
        mine in theirs -> theirs
        tTheirs > tMine -> "$theirs\n\n$mine"
        else -> "$mine\n\n$theirs"
    }
    if (merged.text(field) == text) return merged
    return merged.copy(fields = merged.fields + (field to JsonPrimitive(text)), times = merged.times + (field to maxOf(tMine, tTheirs) + 1))
}
