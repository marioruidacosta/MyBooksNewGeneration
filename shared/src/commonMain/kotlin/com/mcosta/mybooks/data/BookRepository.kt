package com.mcosta.mybooks.data

import com.mcosta.mybooks.data.backup.BackupFormatException
import com.mcosta.mybooks.data.backup.BackupSerializer
import com.mcosta.mybooks.data.backup.Zip
import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.db.BookDao
import com.mcosta.mybooks.data.db.searchPattern
import com.mcosta.mybooks.data.db.toBook
import com.mcosta.mybooks.data.db.toEntity
import com.mcosta.mybooks.data.goodreads.GoodreadsCsv
import com.mcosta.mybooks.data.goodreads.GoodreadsFormatException
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.data.suggestions.HiddenSuggestions
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.Isbn
import com.mcosta.mybooks.domain.MAX_GOAL
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.domain.cleanTags
import com.mcosta.mybooks.share.ShareTracker
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

sealed interface SaveResult {
    data class Saved(val id: Long) : SaveResult
    data object DuplicateIsbn : SaveResult
    data object InvalidTitle : SaveResult
    data object InvalidRating : SaveResult
    data object InvalidIsbn : SaveResult
    data object InvalidReview : SaveResult
}

/** The longest review the community server accepts. */
const val MAX_REVIEW_LENGTH = 2000

data class ImportSummary(val added: Int, val updated: Int, val failed: Int)

/** A tag and how many books have it. */
data class TagCount(val name: String, val count: Int)

/** The longest series or borrower name kept. */
const val MAX_NAME_LENGTH = 100

/** Single entry point for the UI to read and change the library. */
class BookRepository(
    private val dao: BookDao,
    private val lookupService: BookLookupService,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** Runs a block atomically; the app passes a Room write transaction. */
    private val transactionRunner: suspend (suspend () -> Unit) -> Unit = { it() },
    /** Cover photos taken in the app; null in tests that don't involve them. */
    private val coverStore: CoverStore? = null,
    /** Community sharing; null when the community isn't configured (and in most tests). */
    private val shareTracker: ShareTracker? = null,
    /** Reading challenges, kept in backups; null in tests that don't involve them. */
    private val challenges: ChallengeRepository? = null,
    /** Told when a book is marked as Read (to celebrate); null in tests that don't involve it. */
    private val events: ReadingEvents? = null,
    /** Suggestions marked "Not interested", kept in backups; null in tests that don't involve them. */
    private val hiddenSuggestions: HiddenSuggestions? = null,
) {
    fun observeBooks(status: ReadingStatus?, query: String): Flow<List<Book>> =
        dao.observe(status, searchPattern(query)).map { list -> list.map { it.toBook() } }

    fun observeBook(id: Long): Flow<Book?> = dao.observeById(id).map { it?.toBook() }

    suspend fun getBook(id: Long): Book? = dao.getById(id)?.toBook()

    suspend fun bookCount(): Int = dao.getAll().size

    suspend fun findByIsbn(isbn: String): Book? =
        Isbn.normalize(isbn)?.let { dao.findByIsbn(it)?.toBook() }

    /** A book without ISBN, matched on title and authors (ignoring case). */
    suspend fun findByTitleAndAuthors(title: String, authors: String): Book? =
        dao.findWithoutIsbn(title.trim(), authors.trim())?.toBook()

    suspend fun lookup(isbn: String): LookupResult = lookupService.lookup(isbn)

    suspend fun save(book: Book): SaveResult {
        val validated = when (val v = validate(book)) {
            is Validation.Ok -> v.book
            is Validation.Invalid -> return v.result
        }
        if (validated.isbn != null) {
            val existing = dao.findByIsbn(validated.isbn)
            if (existing != null && existing.id != validated.id) return SaveResult.DuplicateIsbn
        }
        return if (validated.id == 0L) {
            val inserted = withStatusDates(validated.copy(dateAdded = clock()))
            val id = dao.insert(inserted.toEntity())
            shareTracker?.changed(null, inserted.copy(id = id))
            SaveResult.Saved(id)
        } else {
            val stored = dao.getById(validated.id)
            val merged = validated.copy(dateAdded = stored?.dateAdded ?: validated.dateAdded)
            val updated = withStatusDates(merged, stored?.status)
            dao.update(updated.toEntity())
            shareTracker?.changed(stored?.toBook(), updated)
            if (stored != null && stored.coverUrl != merged.coverUrl) coverStore?.delete(stored.coverUrl)
            if (stored != null) announceIfFinished(stored.status, updated)
            SaveResult.Saved(validated.id)
        }
    }

    suspend fun setStatus(id: Long, status: ReadingStatus) {
        val before = dao.getById(id)?.status ?: return
        modify(id) { withStatusDates(it.copy(status = status), it.status) }
        dao.getById(id)?.toBook()?.let { announceIfFinished(before, it) }
    }

    /** A book that just became Read is worth celebrating (new, imported and restored books aren't). */
    private fun announceIfFinished(before: ReadingStatus, after: Book) {
        if (before != ReadingStatus.READ && after.status == ReadingStatus.READ) events?.bookFinished(after.id)
    }

    suspend fun setRating(id: Long, rating: Int?) {
        require(rating == null || rating in 1..5) { "rating must be 1..5 or null" }
        modify(id) { it.copy(rating = rating) }
    }

    suspend fun setNotes(id: Long, notes: String) = modify(id) { it.copy(notes = notes) }

    /** The public review (at most [MAX_REVIEW_LENGTH] characters). */
    suspend fun setReview(id: Long, review: String) {
        require(review.length <= MAX_REVIEW_LENGTH) { "review must be at most $MAX_REVIEW_LENGTH characters" }
        modify(id) { it.copy(review = review) }
    }

    /** Progress by page; the percentage follows from the page count when known (capped at 100). */
    suspend fun setProgressPages(id: Long, page: Int) {
        require(page >= 0) { "page must be >= 0" }
        modify(id) { book ->
            val percent = book.pageCount?.takeIf { it > 0 }?.let { minOf(100, page * 100 / it) }
            book.copy(currentPage = page, progressPercent = percent)
        }
    }

    /** Progress by percentage, for books without a page count or read as ebooks. */
    suspend fun setProgressPercent(id: Long, percent: Int) {
        require(percent in 0..100) { "percent must be 0..100" }
        modify(id) { it.copy(currentPage = null, progressPercent = percent) }
    }

    suspend fun setDateStarted(id: Long, date: Long) = modify(id) { it.copy(dateStarted = date) }

    /** When the book was finished (it counts toward that year's challenge); a start date after it is dropped. */
    suspend fun setDateRead(id: Long, date: Long) =
        modify(id) { it.copy(dateRead = date, dateStarted = it.dateStarted?.takeIf { started -> started <= date }) }

    /** The book's tags, tidied by [cleanTags]. */
    suspend fun setTags(id: Long, tags: List<String>) = modify(id) { it.copy(tags = cleanTags(tags)) }

    /** Lent to [person] on [date]; a blank name is ignored. */
    suspend fun lend(id: Long, person: String, date: Long) {
        val name = person.trim().takeIf { it.isNotEmpty() } ?: return
        modify(id) { it.copy(lentTo = name.take(MAX_NAME_LENGTH), lentOn = date) }
    }

    /** The lent book is back home. */
    suspend fun returned(id: Long) = modify(id) { it.copy(lentTo = null, lentOn = null) }

    /** Every tag in the library with how many books have it, most used first, then A to Z. */
    fun observeTags(): Flow<List<TagCount>> = observeBooks(null, "").map { books ->
        books.flatMap { it.tags }.groupBy { it.lowercase() }
            .map { (_, same) -> TagCount(same.first(), same.size) }
            .sortedWith(compareByDescending<TagCount> { it.count }.thenBy { it.name.lowercase() })
    }

    /** Sets the cover (e.g. a new photo) and removes the previous cover photo, if any. */
    suspend fun setCover(id: Long, coverUrl: String?) {
        val old = dao.getById(id)?.coverUrl
        modify(id) { it.copy(coverUrl = coverUrl) }
        if (old != coverUrl) coverStore?.delete(old)
    }

    suspend fun delete(id: Long) {
        val book = dao.getById(id)?.toBook()
        dao.delete(id)
        coverStore?.delete(book?.coverUrl)
        if (book != null) shareTracker?.deleted(book)
    }

    /** Whether the book is excluded from sharing ("Don't share this book"). */
    fun observeShareExcluded(id: Long): Flow<Boolean> = shareTracker?.observeExcluded(id) ?: flowOf(false)

    suspend fun setShareExcluded(id: Long, excluded: Boolean) {
        val book = getBook(id) ?: return
        shareTracker?.setExcluded(book, excluded)
    }

    /** A .zip backup: library.json plus the cover photos taken in the app (covers/<name>). */
    suspend fun exportBackup(): ByteArray {
        val books = dao.getAll().map { it.toBook() }
        val photos = books.mapNotNull { it.coverUrl }.distinct().mapNotNull { ref ->
            val name = coverStore?.nameOf(ref) ?: return@mapNotNull null
            val bytes = coverStore.read(ref) ?: return@mapNotNull null
            (BACKUP_COVERS + name) to bytes
        }
        val library = BackupSerializer.encode(books, clock(), shares(), challenges?.all().orEmpty(), hiddenSuggestions?.keys?.value.orEmpty())
        return Zip.write(listOf(BACKUP_LIBRARY to library.encodeToByteArray()) + photos)
    }

    /** Restores a .zip backup (with cover photos) or an older plain .json backup. */
    suspend fun importBackup(bytes: ByteArray): ImportSummary {
        if (!Zip.isZip(bytes)) return importJson(bytes.decodeToString())
        val entries = Zip.read(bytes)
        val library = entries.firstOrNull { it.first == BACKUP_LIBRARY }?.second?.decodeToString()
            ?: throw BackupFormatException("Not a MyBooks backup file")
        BackupSerializer.decode(library) // validate before writing any photo
        for ((name, data) in entries) {
            val photo = name.removePrefix(BACKUP_COVERS)
            if (name.startsWith(BACKUP_COVERS) && CoverStore.isSafeName(photo)) coverStore?.put(photo, data)
        }
        return importJson(library)
    }

    suspend fun exportJson(): String =
        BackupSerializer.encode(dao.getAll().map { it.toBook() }, clock(), shares(), challenges?.all().orEmpty(), hiddenSuggestions?.keys?.value.orEmpty())

    private suspend fun shares() = shareTracker?.exportShares() ?: emptyMap()

    /**
     * Merges a backup into the library: books whose ISBN is already present are updated, the rest
     * inserted. Invalid entries (and repeats of an ISBN within the file) are counted as failed.
     * Its reading challenges replace the same years' goals.
     * Throws [BackupFormatException] before writing anything if [json] is not a valid backup.
     */
    suspend fun importJson(json: String): ImportSummary {
        val books = BackupSerializer.decodeWithShares(json)
        val goals = BackupSerializer.decodeChallenges(json).filter { it.goal in 1..MAX_GOAL }
        val hidden = BackupSerializer.decodeHiddenSuggestions(json)
        var added = 0
        var updated = 0
        var failed = 0
        transactionRunner {
            val seenIsbns = mutableSetOf<String>()
            for ((raw, share) in books) {
                val book = (validate(raw.copy(id = 0)) as? Validation.Ok)?.book
                if (book == null || (book.isbn != null && !seenIsbns.add(book.isbn))) {
                    failed++
                    continue
                }
                // Books without ISBN match on title + authors, so restoring the same backup twice doesn't duplicate them.
                val existing = if (book.isbn != null) {
                    dao.findByIsbn(book.isbn)
                } else {
                    dao.findWithoutIsbn(book.title, book.authors.trim())
                }
                if (existing != null) {
                    dao.update(withStatusDates(book.copy(id = existing.id, dateAdded = existing.dateAdded), book.status).toEntity())
                    if (share != null) shareTracker?.restored(existing.id, share)
                    updated++
                } else {
                    val dateAdded = if (book.dateAdded > 0) book.dateAdded else clock()
                    val id = dao.insert(withStatusDates(book.copy(dateAdded = dateAdded), book.status).toEntity())
                    if (share != null) shareTracker?.restored(id, share)
                    added++
                }
            }
        }
        for (goal in goals) challenges?.setGoal(goal.year, goal.goal)
        hiddenSuggestions?.addAll(hidden)
        return ImportSummary(added, updated, failed)
    }

    /** The library as a Goodreads CSV (for goodreads.com's import). */
    suspend fun exportGoodreads(): String = GoodreadsCsv.write(dao.getAll().map { it.toBook() })

    /**
     * Merges a Goodreads library export. A book already here (same ISBN, or same title and authors when it has
     * none) takes Goodreads' shelf, date read, and its rating and review when Goodreads has them; other details
     * are only filled in where empty, so covers, notes and progress stay. Goodreads has no start dates and many
     * books have no read date: none are invented. Covers come afterwards ([findMissingCovers]). Like a backup
     * restore, nothing is posted to the community. Throws [GoodreadsFormatException] before
     * writing anything if [csv] is not a Goodreads export.
     */
    suspend fun importGoodreads(csv: String): ImportSummary {
        val books = GoodreadsCsv.read(csv)
        var added = 0
        var updated = 0
        var failed = 0
        transactionRunner {
            val seenIsbns = mutableSetOf<String>()
            for (raw in books) {
                val book = (validate(raw) as? Validation.Ok)?.book
                if (book == null || (book.isbn != null && !seenIsbns.add(book.isbn))) {
                    failed++
                    continue
                }
                val existing = (if (book.isbn != null) dao.findByIsbn(book.isbn) else null)
                    ?: dao.findWithoutIsbn(book.title, book.authors.trim())
                if (existing != null) {
                    val mine = existing.toBook()
                    val merged = mine.copy(
                        status = book.status,
                        dateRead = book.dateRead ?: mine.dateRead,
                        rating = book.rating ?: mine.rating,
                        review = book.review.ifEmpty { mine.review },
                        isbn = mine.isbn ?: book.isbn,
                        authors = mine.authors.ifBlank { book.authors },
                        publisher = mine.publisher ?: book.publisher,
                        pageCount = mine.pageCount ?: book.pageCount,
                        publishedYear = mine.publishedYear ?: book.publishedYear,
                        notes = mine.notes.ifEmpty { book.notes },
                        // Shelves added on Goodreads join your tags; none are taken away.
                        tags = cleanTags(mine.tags + book.tags),
                        series = mine.series ?: book.series,
                        seriesNumber = if (mine.series != null) mine.seriesNumber else book.seriesNumber,
                    )
                    dao.update(withStatusDates(merged, book.status).toEntity())
                    updated++
                } else {
                    val dateAdded = if (book.dateAdded > 0) book.dateAdded else clock()
                    dao.insert(withStatusDates(book.copy(dateAdded = dateAdded), book.status).toEntity())
                    added++
                }
            }
        }
        return ImportSummary(added, updated, failed)
    }

    /**
     * Looks up a cover for every book with an ISBN and no cover, [parallel] books at a time (one at a time took
     * minutes), with the same sources as adding a book, asked at once; an empty
     * description and page count are filled in too. Like an import, this isn't posted to the community.
     * [onProgress] gets (books done, books to look up, covers found). Returns the covers found.
     */
    suspend fun findMissingCovers(
        parallel: Int = 8,
        onProgress: (done: Int, total: Int, found: Int) -> Unit = { _, _, _ -> },
    ): Int {
        val todo = dao.getAll().map { it.toBook() }.filter { it.isbn != null && it.coverUrl == null }.map { it.id }
        val slots = Semaphore(parallel)
        val writes = Mutex()
        var done = 0
        var found = 0
        onProgress(0, todo.size, 0)
        coroutineScope {
            for (id in todo) {
                launch {
                    slots.withPermit {
                        val isbn = dao.getById(id)?.toBook()?.takeIf { it.coverUrl == null }?.isbn
                        val match = isbn?.let { lookupService.lookupCover(it) }
                        writes.withLock {
                            // Read again: the book may have been edited or deleted during the lookup.
                            val book = dao.getById(id)?.toBook()
                            if (match?.coverUrl != null && book != null && book.coverUrl == null) {
                                val description = book.description ?: match.description
                                val pages = book.pageCount ?: match.pageCount
                                dao.update(book.copy(coverUrl = match.coverUrl, description = description, pageCount = pages).toEntity())
                                found++
                            }
                            done++
                            onProgress(done, todo.size, found)
                        }
                    }
                }
            }
        }
        return found
    }

    /**
     * Looks up and keeps a description for a book that has none (it needs an ISBN); returns whether one was
     * found. Like the cover search, this isn't posted to the community.
     */
    suspend fun fillDescription(id: Long): Boolean {
        val isbn = dao.getById(id)?.toBook()?.takeIf { it.description.isNullOrBlank() }?.isbn ?: return false
        val text = lookupService.lookupDescription(isbn) ?: return false
        // Read again: the book may have been edited or deleted during the lookup.
        val book = dao.getById(id)?.toBook()?.takeIf { it.description.isNullOrBlank() } ?: return false
        dao.update(book.copy(description = text).toEntity())
        return true
    }

    private suspend fun modify(id: Long, change: (Book) -> Book) {
        val book = dao.getById(id)?.toBook() ?: return
        val changed = change(book)
        dao.update(changed.toEntity())
        shareTracker?.changed(book, changed)
    }

    /**
     * Status rules: moving to Reading records a start date; moving to Read records a read date and keeps the
     * start date and progress as history; To read / To buy mean "not started", so start date and progress are
     * cleared. Only a change from [previous] records today's date: a book that already had its status (restored,
     * imported, edited) keeps the dates it has, even none. A start date after the read date is dropped.
     */
    private fun withStatusDates(book: Book, previous: ReadingStatus? = null): Book = when (book.status) {
        ReadingStatus.READING ->
            book.copy(dateRead = null, dateStarted = book.dateStarted ?: clock().takeIf { previous != ReadingStatus.READING })
        ReadingStatus.READ -> {
            val read = book.dateRead ?: clock().takeIf { previous != ReadingStatus.READ }
            book.copy(dateRead = read, dateStarted = book.dateStarted?.takeIf { read == null || it <= read })
        }
        ReadingStatus.TO_READ, ReadingStatus.WISHLIST ->
            book.copy(dateRead = null, dateStarted = null, currentPage = null, progressPercent = null)
    }

    private companion object {
        const val BACKUP_LIBRARY = "library.json"
        const val BACKUP_COVERS = "covers/"
    }

    internal sealed interface Validation {
        data class Ok(val book: Book) : Validation
        data class Invalid(val result: SaveResult) : Validation
    }

    /** Applies the library rules shared by [save] and backup import. */
    internal fun validate(book: Book): Validation {
        val title = book.title.trim()
        if (title.isEmpty()) return Validation.Invalid(SaveResult.InvalidTitle)
        if (book.rating != null && book.rating !in 1..5) return Validation.Invalid(SaveResult.InvalidRating)
        if (book.review.length > MAX_REVIEW_LENGTH) return Validation.Invalid(SaveResult.InvalidReview)
        val isbn = book.isbn?.takeIf { it.isNotBlank() }?.let {
            Isbn.normalize(it) ?: return Validation.Invalid(SaveResult.InvalidIsbn)
        }
        val series = book.series?.trim()?.take(MAX_NAME_LENGTH)?.takeIf { it.isNotEmpty() }
        val lentTo = book.lentTo?.trim()?.take(MAX_NAME_LENGTH)?.takeIf { it.isNotEmpty() }
        return Validation.Ok(
            book.copy(
                title = title,
                isbn = isbn,
                currentPage = book.currentPage?.takeIf { it >= 0 },
                progressPercent = book.progressPercent?.coerceIn(0, 100),
                tags = cleanTags(book.tags),
                series = series,
                // A number only means something within a series ("#2" of nothing is dropped).
                seriesNumber = book.seriesNumber?.trim()?.removePrefix("#")?.trim()?.take(10)?.takeIf { series != null && it.isNotEmpty() },
                lentTo = lentTo,
                lentOn = book.lentOn.takeIf { lentTo != null },
            )
        )
    }
}
