package com.mcosta.mybooks.data.ebooks

import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.db.BookmarkDao
import com.mcosta.mybooks.data.db.BookmarkEntity
import com.mcosta.mybooks.data.db.EbookDao
import com.mcosta.mybooks.data.db.EbookEntity
import com.mcosta.mybooks.data.db.toBookmark
import com.mcosta.mybooks.data.db.toEbook
import com.mcosta.mybooks.domain.Bookmark
import com.mcosta.mybooks.domain.Ebook
import com.mcosta.mybooks.domain.EbookFormat
import com.mcosta.mybooks.domain.ReadingStatus
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import okio.Source
import kotlin.math.roundToInt
import kotlin.time.Clock

/** A book's ebook and whether its file is on this phone (it isn't after a backup is restored elsewhere). */
data class EbookState(val ebook: Ebook, val fileOnPhone: Boolean)

/** The progress at which the reader asks "Finished? Mark as Read". */
const val FINISH_PERCENT = 99

/**
 * Books' ebook files, reading positions and bookmarks. Positions become the book's progress while it is being
 * read; a Read book keeps its status and progress when it is read again.
 */
class EbookRepository(
    private val ebooks: EbookDao,
    private val bookmarks: BookmarkDao,
    private val files: EbookFiles,
    private val books: BookRepository,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /** Books asked "Finished?" since they last went below [FINISH_PERCENT]. */
    private val prompted = mutableSetOf<Long>()

    fun observe(bookId: Long): Flow<EbookState?> = ebooks.observe(bookId).map { row ->
        row?.let { EbookState(it.toEbook(), files.exists(bookId, it.format)) }
    }

    fun fileOf(ebook: Ebook) = files.path(ebook.bookId, ebook.format)

    /**
     * Stores [source] as the book's ebook, replacing any other. Adding the same format again after the file went
     * missing (a restored backup) keeps the reading position; a different file starts from the beginning.
     */
    suspend fun add(bookId: Long, format: EbookFormat, fileName: String, source: Source) {
        val old = ebooks.get(bookId)
        if (old != null && old.format != format) files.delete(bookId, old.format)
        val keepPosition = old != null && old.format == format && !files.exists(bookId, format)
        val size = files.store(bookId, format, source)
        try {
            ebooks.upsert(
                EbookEntity(
                    bookId, format, fileName, size, clock(),
                    position = old?.position.takeIf { keepPosition },
                    lastOpened = old?.lastOpened.takeIf { keepPosition },
                ),
            )
        } catch (e: Throwable) {
            // Not recorded (an error, or the screen closed): no file is left that nothing knows about.
            withContext(NonCancellable) { files.delete(bookId, format) }
            throw e
        }
    }

    /** Whether adding a [format] file to the book starts reading from the beginning (a file on the phone is replaced, or the format changes). */
    suspend fun addingRestarts(bookId: Long, format: EbookFormat): Boolean {
        val old = ebooks.get(bookId) ?: return false
        return old.format != format || files.exists(bookId, old.format)
    }

    /** Whether the book's ebook file is on this phone. */
    suspend fun fileOnPhone(bookId: Long): Boolean = ebooks.get(bookId)?.let { files.exists(bookId, it.format) } == true

    /** Deletes the file; the place, bookmarks and highlights stay for when it is added again. */
    suspend fun remove(bookId: Long) {
        val old = ebooks.get(bookId) ?: return
        files.delete(bookId, old.format)
    }

    /** The book was opened in the reader: To read and To buy books are now being read. */
    suspend fun opened(bookId: Long) {
        val book = books.getBook(bookId) ?: return
        if (book.status == ReadingStatus.TO_READ || book.status == ReadingStatus.WISHLIST) books.setStatus(bookId, ReadingStatus.READING)
    }

    /**
     * Saves the reader's [position] (locator JSON) and, for a book being read, its [progression] (0–1) as progress.
     * Returns true when the reader should ask "Finished? Mark as Read": the first time it reaches [FINISH_PERCENT]
     * since it was last below it.
     */
    suspend fun savePosition(bookId: Long, position: String, progression: Double): Boolean {
        ebooks.setPosition(bookId, position, clock())
        val book = books.getBook(bookId) ?: return false
        if (book.status != ReadingStatus.READING) return false
        val percent = (progression * 100).roundToInt().coerceIn(0, 100)
        if (book.progressPercent != percent || book.currentPage != null) books.setProgressPercent(bookId, percent)
        if (percent < FINISH_PERCENT) {
            prompted -= bookId
            return false
        }
        return prompted.add(bookId)
    }

    fun observeBookmarks(bookId: Long): Flow<List<Bookmark>> = bookmarks.observe(bookId).map { list -> list.map { it.toBookmark() } }

    suspend fun addBookmark(bookId: Long, position: String, label: String): Long =
        bookmarks.insert(BookmarkEntity(bookId = bookId, position = position, label = label, dateAdded = clock()))

    suspend fun deleteBookmark(id: Long) = bookmarks.delete(id)
}
