package com.mcosta.mybooks.data.ebooks

import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.FakeBookmarkDao
import com.mcosta.mybooks.data.FakeEbookDao
import com.mcosta.mybooks.data.FakeQuoteDao
import com.mcosta.mybooks.data.SaveResult
import com.mcosta.mybooks.data.db.QuoteEntity
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.EbookFormat
import com.mcosta.mybooks.domain.ReadingStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class EbookRepositoryTest {
    private val fs = FakeFileSystem()
    private val files = EbookFiles(fs, "/files/ebooks".toPath())
    private val ebookDao = FakeEbookDao()
    private val bookmarkDao = FakeBookmarkDao()
    private val quoteDao = FakeQuoteDao()
    private var now = 1000L
    private val books = BookRepository(
        FakeBookDao(), object : BookLookupService {
            override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
        },
        clock = { now }, quotes = quoteDao, ebooks = ebookDao, bookmarks = bookmarkDao, ebookFiles = files,
    )
    private val repo = EbookRepository(ebookDao, bookmarkDao, files, books, clock = { now })

    private suspend fun book(status: ReadingStatus = ReadingStatus.TO_READ) =
        (books.save(Book(title = "Os Maias", authors = "Eça de Queirós", status = status)) as SaveResult.Saved).id

    private fun bytes(size: Int) = ByteArray(size) { (it % 251).toByte() }
    private fun source(data: ByteArray) = Buffer().write(data)

    @Test
    fun addingStreamsTheFileAndRecordsIt() = runTest {
        val id = book()
        val data = bytes(5 * 1024 * 1024)
        repo.add(id, EbookFormat.PDF, "os maias.pdf", source(data))
        val state = repo.observe(id).first()!!
        assertEquals(5_242_880L, state.ebook.size)
        assertEquals("os maias.pdf", state.ebook.fileName)
        assertTrue(state.fileOnPhone)
        assertTrue(data.contentEquals(fs.read(files.path(id, EbookFormat.PDF)) { readByteArray() }))
    }

    @Test
    fun aSecondFileReplacesTheFirst() = runTest {
        val id = book()
        repo.add(id, EbookFormat.PDF, "a.pdf", source(bytes(10)))
        repo.add(id, EbookFormat.EPUB, "b.epub", source(bytes(20)))
        val state = repo.observe(id).first()!!
        assertEquals(EbookFormat.EPUB to 20L, state.ebook.format to state.ebook.size)
        assertFalse(files.exists(id, EbookFormat.PDF))
        assertEquals(1, ebookDao.getAll().size)
    }

    @Test
    fun removeKeepsThePlaceBookmarksAndQuotes() = runTest {
        // "Your place, bookmarks and highlights stay, for if you add it again."
        val id = book()
        repo.add(id, EbookFormat.EPUB, "b.epub", source(bytes(20)))
        repo.savePosition(id, "{\"p\":5}", 0.5)
        repo.addBookmark(id, "{}", "Chapter 1")
        quoteDao.insert(QuoteEntity(bookId = id, text = "x", page = null, dateAdded = 1, position = "{}"))
        repo.remove(id)
        val state = repo.observe(id).first()!!
        assertFalse(state.fileOnPhone)
        assertFalse(files.exists(id, EbookFormat.EPUB))
        assertEquals(1, repo.observeBookmarks(id).first().size)
        assertEquals(1, quoteDao.forBook(id).size)
        repo.add(id, EbookFormat.EPUB, "b.epub", source(bytes(20)))
        assertEquals("{\"p\":5}", ebookDao.get(id)!!.position)
    }

    @Test
    fun aFailedSaveLeavesNoFileBehind() = runTest {
        val id = book()
        ebookDao.failUpsert = true
        assertFailsWith<IllegalStateException> { repo.add(id, EbookFormat.EPUB, "b.epub", source(bytes(20))) }
        assertFalse(files.exists(id, EbookFormat.EPUB))
    }

    @Test
    fun deletingTheBookDeletesEverything() = runTest {
        val id = book()
        repo.add(id, EbookFormat.EPUB, "b.epub", source(bytes(20)))
        repo.addBookmark(id, "{}", "Chapter 1")
        books.delete(id)
        assertNull(ebookDao.get(id))
        assertFalse(files.exists(id, EbookFormat.EPUB))
        assertTrue(bookmarkDao.getAll().isEmpty())
    }

    @Test
    fun openingMovesToReadAndToBuyToReading() = runTest {
        for (status in listOf(ReadingStatus.TO_READ, ReadingStatus.WISHLIST)) {
            val id = book(status)
            repo.opened(id)
            val b = books.getBook(id)!!
            assertEquals(ReadingStatus.READING, b.status)
            assertNotNull(b.dateStarted)
        }
    }

    @Test
    fun openingAReadBookChangesNothing() = runTest {
        val id = book(ReadingStatus.READ)
        val before = books.getBook(id)!!
        repo.opened(id)
        assertEquals(before, books.getBook(id))
    }

    @Test
    fun positionBecomesProgress() = runTest {
        val id = book(ReadingStatus.READING)
        repo.add(id, EbookFormat.EPUB, "b.epub", source(bytes(20)))
        now = 2000
        assertFalse(repo.savePosition(id, "{\"p\":1}", 0.424))
        assertEquals(42, books.getBook(id)!!.progressPercent)
        assertEquals("{\"p\":1}" to 2000L, ebookDao.get(id)!!.let { it.position to it.lastOpened })
    }

    /** Reaching the end as the reader closed: nobody saw "Finished?", so the next opening asks, once. */
    @Test
    fun finishedIsAskedOnOpeningWhenTheReaderClosedAtTheEnd() = runTest {
        val id = book(ReadingStatus.READING)
        repo.add(id, EbookFormat.EPUB, "b.epub", source(bytes(20)))
        assertTrue(repo.savePosition(id, "{}", 0.995))
        repo.forgetFinishPrompt(id)
        assertTrue(repo.finishPromptOnOpen(id))
        assertFalse(repo.finishPromptOnOpen(id))
        assertFalse(repo.savePosition(id, "{}", 1.0))
    }

    @Test
    fun notAskedOnOpeningBeforeTheEndOrWhenNotReading() = runTest {
        val reading = book(ReadingStatus.READING)
        repo.add(reading, EbookFormat.EPUB, "b.epub", source(bytes(20)))
        repo.savePosition(reading, "{}", 0.5)
        assertFalse(repo.finishPromptOnOpen(reading))
        val read = book(ReadingStatus.READ)
        assertFalse(repo.finishPromptOnOpen(read))
    }

    @Test
    fun finishPromptAt99OnceUntilBelowAgain() = runTest {
        val id = book(ReadingStatus.READING)
        repo.add(id, EbookFormat.EPUB, "b.epub", source(bytes(20)))
        assertFalse(repo.savePosition(id, "{}", 0.98))
        assertTrue(repo.savePosition(id, "{}", 0.99))
        assertFalse(repo.savePosition(id, "{}", 1.0))
        assertFalse(repo.savePosition(id, "{}", 0.5))
        assertTrue(repo.savePosition(id, "{}", 0.995))
    }

    /** Review focus: rereading a finished book keeps it Read, with its progress, and never asks again. */
    @Test
    fun aReadBookKeepsItsProgress() = runTest {
        val id = book(ReadingStatus.READ)
        repo.add(id, EbookFormat.EPUB, "b.epub", source(bytes(20)))
        val before = books.getBook(id)!!
        assertFalse(repo.savePosition(id, "{\"p\":9}", 1.0))
        assertEquals(before, books.getBook(id))
        assertEquals("{\"p\":9}", ebookDao.get(id)!!.position)
    }

    @Test
    fun missingFileIsReported() = runTest {
        val id = book()
        repo.add(id, EbookFormat.EPUB, "b.epub", source(bytes(20)))
        fs.delete(files.path(id, EbookFormat.EPUB))
        assertFalse(repo.observe(id).first()!!.fileOnPhone)
    }

    /** After a backup is restored, adding the file again keeps the reading position. */
    @Test
    fun addingAMissingFileAgainKeepsThePosition() = runTest {
        val id = book(ReadingStatus.READING)
        repo.add(id, EbookFormat.EPUB, "b.epub", source(bytes(20)))
        repo.savePosition(id, "{\"p\":5}", 0.5)
        fs.delete(files.path(id, EbookFormat.EPUB))
        repo.add(id, EbookFormat.EPUB, "b.epub", source(bytes(20)))
        assertEquals("{\"p\":5}", ebookDao.get(id)!!.position)
    }

    @Test
    fun bookmarksAreAddedAndDeleted() = runTest {
        val id = book()
        val first = repo.addBookmark(id, "{\"p\":1}", "Chapter 1")
        repo.addBookmark(id, "{\"p\":2}", "Chapter 2")
        repo.deleteBookmark(first)
        assertEquals(listOf("Chapter 2"), repo.observeBookmarks(id).first().map { it.label })
    }
}
