package com.mcosta.mybooks.ui.ebooks

import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.FakeBookmarkDao
import com.mcosta.mybooks.data.FakeEbookDao
import com.mcosta.mybooks.data.SaveResult
import com.mcosta.mybooks.data.ebooks.EbookFiles
import com.mcosta.mybooks.data.ebooks.EbookMatcher
import com.mcosta.mybooks.data.ebooks.EbookRepository
import com.mcosta.mybooks.data.ebooks.Match
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.EbookFormat
import com.mcosta.mybooks.domain.EbookMetadata
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.platform.IncomingFile
import com.mcosta.mybooks.platform.Inspection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddEbookViewModelTest : MainDispatcherTest() {
    private val fs = FakeFileSystem()
    private val ebookDao = FakeEbookDao()
    private val books = BookRepository(FakeBookDao(), object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
    }, ebooks = ebookDao)
    private val ebooks = EbookRepository(ebookDao, FakeBookmarkDao(), EbookFiles(fs, "/files/ebooks".toPath(), io = kotlinx.coroutines.Dispatchers.Unconfined), books)
    private val incoming = IncomingFile("/cache/incoming/os-maias.epub", "os-maias.epub", 3)

    init {
        fs.createDirectories("/cache/incoming".toPath())
        fs.write(incoming.path.toPath()) { write(byteArrayOf(1, 2, 3)) }
    }

    private val maias = EbookMetadata("Os Maias", listOf("Eça de Queirós"), null, "pt")

    private fun vm(inspection: Inspection, forBookId: Long? = null) =
        AddEbookViewModel(incoming, { inspection }, EbookMatcher(books), ebooks, books, fs, forBookId)

    @Test
    fun readableFileMatchedThenReadNow() = runTest {
        val vm = vm(Inspection.Readable(EbookFormat.EPUB, maias))
        val ready = assertIs<AddEbookState.Ready>(vm.state.value)
        assertIs<Match.New>(ready.match)
        vm.readNow()
        val done = assertIs<AddEbookState.Done>(vm.state.value)
        assertTrue(done.read)
        val book = books.getBook(done.bookId)!!
        assertEquals("Os Maias" to ReadingStatus.READING, book.title to book.status)
        assertTrue(ebooks.observe(done.bookId).first()!!.fileOnPhone)
        assertFalse(fs.exists(incoming.path.toPath()))     // the temporary copy is cleaned up
    }

    @Test
    fun addToReadKeepsStatus() = runTest {
        val id = (books.save(Book(title = "Os Maias", authors = "Eça de Queirós", status = ReadingStatus.READ)) as SaveResult.Saved).id
        val vm = vm(Inspection.Readable(EbookFormat.EPUB, maias))
        assertEquals(id, assertIs<Match.Existing>(assertIs<AddEbookState.Ready>(vm.state.value).match).book.id)
        vm.addToRead()
        assertEquals(AddEbookState.Done(id, read = false), vm.state.value)
        assertEquals(ReadingStatus.READ, books.getBook(id)!!.status)
    }

    @Test
    fun adobeFileAddsWithoutFile() = runTest {
        val vm = vm(Inspection.AdobeProtected(maias))
        assertIs<Match.New>(assertIs<AddEbookState.AdobeProtected>(vm.state.value).match)
        vm.addWithoutFile()
        val done = assertIs<AddEbookState.Done>(vm.state.value)
        assertEquals("Os Maias", books.getBook(done.bookId)!!.title)
        assertNull(ebookDao.get(done.bookId))
    }

    @Test
    fun acsmUsesItsTitle() = runTest {
        val vm = vm(Inspection.AdobeProtected(EbookMetadata("A Cidade e as Serras", listOf("Eça de Queirós"), null, null)))
        assertEquals("A Cidade e as Serras", assertIs<AddEbookState.AdobeProtected>(vm.state.value).match.book.title)
    }

    @Test
    fun otherProtectionAndUnreadable() = runTest {
        assertEquals("os maias", assertIs<AddEbookState.Protected>(vm(Inspection.Protected).state.value).match.book.title.lowercase().replace('-', ' '))
        assertEquals(AddEbookState.Unreadable, vm(Inspection.Unreadable).state.value)
    }

    @Test
    fun replacingAsksFirst() = runTest {
        val id = (books.save(Book(title = "Os Maias", authors = "Eça de Queirós")) as SaveResult.Saved).id
        ebooks.add(id, EbookFormat.PDF, "old.pdf", okio.Buffer().write(byteArrayOf(9)))
        val ready = assertIs<AddEbookState.Ready>(vm(Inspection.Readable(EbookFormat.EPUB, maias)).state.value)
        assertTrue(ready.replaces)
    }

    /** From a book's page: the file goes to that book, whatever its metadata says. */
    @Test
    fun aFileForAGivenBookSkipsMatching() = runTest {
        val id = (books.save(Book(title = "Something else")) as SaveResult.Saved).id
        val vm = vm(Inspection.Readable(EbookFormat.EPUB, maias), forBookId = id)
        assertEquals(id, assertIs<Match.Existing>(assertIs<AddEbookState.Ready>(vm.state.value).match).book.id)
    }

    /** After a restore the book has an ebook but no file: adding it again keeps the place, so no warning. */
    @Test
    fun addingAMissingFileAgainDoesNotWarn() = runTest {
        val id = (books.save(Book(title = "Os Maias", authors = "Eça de Queirós")) as SaveResult.Saved).id
        ebooks.add(id, EbookFormat.EPUB, "old.epub", okio.Buffer().write(byteArrayOf(9)))
        assertTrue(assertIs<AddEbookState.Ready>(vm(Inspection.Readable(EbookFormat.EPUB, maias)).state.value).replaces)
        ebooks.remove(id)
        assertFalse(assertIs<AddEbookState.Ready>(vm(Inspection.Readable(EbookFormat.EPUB, maias)).state.value).replaces)
        // A different format can't keep the place.
        assertTrue(assertIs<AddEbookState.Ready>(vm(Inspection.Readable(EbookFormat.PDF, maias)).state.value).replaces)
    }

    /** A file whose name gives no title ("_.epub") is added as "Untitled", not a crash. */
    @Test
    fun aNamelessProtectedFileIsAddedAsUntitled() = runTest {
        val nameless = IncomingFile("/cache/incoming/_.epub", "_.epub", 3)
        fs.write(nameless.path.toPath()) { write(byteArrayOf(1, 2, 3)) }
        val vm = AddEbookViewModel(nameless, { Inspection.Protected }, EbookMatcher(books), ebooks, books, fs)
        vm.addWithoutFile()
        val done = assertIs<AddEbookState.Done>(vm.state.value)
        assertEquals("Untitled", books.getBook(done.bookId)!!.title)
    }
}
