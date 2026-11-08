package com.mcosta.mybooks.ui.ebooks

import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.FakeBookmarkDao
import com.mcosta.mybooks.data.FakeEbookDao
import com.mcosta.mybooks.data.ebooks.Classic
import com.mcosta.mybooks.data.ebooks.ClassicsSource
import com.mcosta.mybooks.data.ebooks.ClassicsUnavailable
import com.mcosta.mybooks.data.ebooks.EbookFiles
import com.mcosta.mybooks.data.ebooks.EbookMatcher
import com.mcosta.mybooks.data.ebooks.EbookRepository
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.ReadingStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Path.Companion.toPath
import okio.Source
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClassicsViewModelTest : MainDispatcherTest() {
    private val maias = Classic(18220, "Os Maias", listOf("Eça de Queirós"), listOf("pt"), "https://g/18220.epub", null)
    private var gate: CompletableDeferred<Unit>? = null
    private var failSearch = false
    private var failDownload = false
    private var downloadGate: CompletableDeferred<Unit>? = null
    private var downloads = 0
    private val source = object : ClassicsSource {
        override suspend fun search(query: String): List<Classic> {
            gate?.await()
            if (failSearch) throw ClassicsUnavailable()
            return listOf(maias)
        }
        override suspend fun download(classic: Classic, into: suspend (Source) -> Unit) {
            downloads++
            downloadGate?.await()
            if (failDownload) throw ClassicsUnavailable()
            into(Buffer().write(ByteArray(100)))
        }
    }
    private val ebookDao = FakeEbookDao()
    private val books = BookRepository(FakeBookDao(), object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
    }, ebooks = ebookDao)
    private val ebooks = EbookRepository(ebookDao, FakeBookmarkDao(), EbookFiles(FakeFileSystem(), "/e".toPath(), io = kotlinx.coroutines.Dispatchers.Unconfined), books)
    private val vm = ClassicsViewModel(source, EbookMatcher(books), ebooks, books)

    @Test
    fun searchShowsResults() = runTest {
        vm.search("maias")
        assertEquals(ClassicsState.Results(listOf(maias)), vm.state.value.search)
    }

    @Test
    fun slowSearchShowsSearchingThenResults() = runTest {
        gate = CompletableDeferred()
        vm.search("maias")
        assertEquals(ClassicsState.Searching, vm.state.value.search)
        gate!!.complete(Unit)
        assertIs<ClassicsState.Results>(vm.state.value.search)
    }

    @Test
    fun failedSearchCanRetry() = runTest {
        failSearch = true
        vm.search("maias")
        assertEquals(ClassicsState.Failed, vm.state.value.search)
        failSearch = false
        vm.retry()
        assertIs<ClassicsState.Results>(vm.state.value.search)
    }

    @Test
    fun downloadCreatesToReadBookWithFile() = runTest {
        vm.search("maias")
        vm.download(maias)
        val bookId = vm.state.value.downloaded.getValue(maias.id)
        val book = books.getBook(bookId)!!
        assertEquals(Triple("Os Maias", "Eça de Queirós", ReadingStatus.TO_READ), Triple(book.title, book.authors, book.status))
        assertTrue(ebooks.observe(bookId).first()!!.fileOnPhone)
    }

    @Test
    fun failedDownloadLeavesNoBook() = runTest {
        failDownload = true
        vm.search("maias")
        vm.download(maias)
        assertTrue(books.observeBooks(null, "").first().isEmpty())
        assertEquals("Couldn't download the book. Check the connection and try again.", vm.state.value.message)
        assertTrue(maias.id !in vm.state.value.downloading)
    }

    /** A later visit: the classic is already in the library with its file; downloading again would lose the place. */
    @Test
    fun aClassicAlreadyOnThePhoneIsNotDownloadedAgain() = runTest {
        vm.download(maias)
        val id = vm.state.value.downloaded.getValue(maias.id)
        ebooks.savePosition(id, "{\"p\":7}", 0.4)
        val later = ClassicsViewModel(source, EbookMatcher(books), ebooks, books)
        later.download(maias)
        assertEquals(id, later.state.value.downloaded[maias.id])
        assertEquals(1, downloads)
        assertEquals("{\"p\":7}", ebooks.observe(id).first()!!.ebook.position)
    }

    /** Leaving Free classics while a book downloads leaves no new book behind. */
    @Test
    fun leavingDuringADownloadLeavesNoBook() = runTest {
        downloadGate = CompletableDeferred()
        vm.download(maias)
        assertEquals(1, books.observeBooks(null, "").first().size)
        vm.viewModelScope.cancel()
        assertTrue(books.observeBooks(null, "").first().isEmpty())
    }
}
