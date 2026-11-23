package com.mcosta.mybooks.ui.scan

import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.SaveResult
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.physical.onShelf
import com.mcosta.mybooks.ui.edit.EditBookViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ShelfScanTest : MainDispatcherTest() {
    private val emma = "9780141439587"
    private val stoner = "9781590171998"
    private val remains = "9780679731726"
    private val unknown = "9780441172719"

    private val dao = FakeBookDao()
    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val repo = BookRepository(dao, object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult {
            gates[isbn]?.await()
            return if (isbn == unknown) LookupResult.NotFound else LookupResult.Found(Book(title = "Title $isbn", authors = "Author"))
        }
    })

    private suspend fun all() = repo.observeBooksNow()

    private suspend fun BookRepository.observeBooksNow(): List<Book> = dao.getAll().map {
        assertNotNull(getBook(it.id))
    }

    private suspend fun saved(book: Book): Long = (repo.save(book) as SaveResult.Saved).id

    private fun result(vm: BatchScanViewModel, isbn: String) = vm.state.value.items.first { it.isbn == isbn }.result

    @Test
    fun aBookAddedByHandShowsAsPlaced() = runTest {
        val vm = BatchScanViewModel(repo, "s1")
        vm.scanned(unknown)
        advanceUntilIdle()
        assertIs<ScanResult.NotFound>(result(vm, unknown))

        val id = saved(Book(isbn = unknown, title = "By hand"))
        repo.placeAtEnd(id, "s1")
        advanceUntilIdle()

        assertIs<ScanResult.Placed>(result(vm, unknown))
        assertEquals(1, vm.state.value.placed)
        // Undo takes it off the shelf and keeps what was typed in.
        vm.undo(unknown)
        advanceUntilIdle()
        assertNull(assertNotNull(repo.getBook(id)).shelfId)
    }

    @Test
    fun scanningBuildsTheOrder() = runTest {
        val vm = BatchScanViewModel(repo, "s1")
        for (isbn in listOf(emma, stoner, remains)) vm.scanned(isbn)
        advanceUntilIdle()
        val shelf = all().onShelf("s1")
        assertEquals(listOf(emma, stoner, remains), shelf.map { it.isbn })
        assertEquals(listOf(1.0, 2.0, 3.0), shelf.map { it.shelfPosition })
    }

    @Test
    fun aSlowLookupKeepsTheScanOrder() = runTest {
        val slow = CompletableDeferred<Unit>()
        gates[emma] = slow
        val vm = BatchScanViewModel(repo, "s1")
        vm.scanned(emma)
        vm.scanned(stoner)
        advanceUntilIdle()
        slow.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(emma, stoner), all().onShelf("s1").map { it.isbn })
    }

    @Test
    fun aBookFromAnotherShelfMoves() = runTest {
        val id = saved(Book(isbn = emma, title = "Emma", shelfId = "other", shelfPosition = 7.0))
        val vm = BatchScanViewModel(repo, "s1")
        vm.scanned(emma)
        advanceUntilIdle()
        assertEquals("s1", repo.getBook(id)?.shelfId)
        assertIs<ScanResult.Placed>(result(vm, emma))
        vm.undo(emma)
        advanceUntilIdle()
        assertEquals("other", repo.getBook(id)?.shelfId)
        assertEquals(7.0, repo.getBook(id)?.shelfPosition)
    }

    @Test
    fun alreadyOnThisShelfStaysPut() = runTest {
        val id = saved(Book(isbn = emma, title = "Emma", shelfId = "s1", shelfPosition = 2.0))
        saved(Book(isbn = stoner, title = "Stoner", shelfId = "s1", shelfPosition = 5.0))
        val vm = BatchScanViewModel(repo, "s1")
        vm.scanned(emma)
        advanceUntilIdle()
        assertIs<ScanResult.AlreadyOnShelf>(result(vm, emma))
        assertEquals(2.0, repo.getBook(id)?.shelfPosition)
    }

    @Test
    fun aNewBookIsAddedAsToReadAndPlaced() = runTest {
        val vm = BatchScanViewModel(repo, "s1")
        vm.scanned(emma)
        advanceUntilIdle()
        val book = all().single()
        assertEquals(ReadingStatus.TO_READ, book.status)
        assertEquals("s1", book.shelfId)
        vm.undo(emma)
        advanceUntilIdle()
        assertEquals(0, all().size)
    }

    @Test
    fun aToBuyBookBecomesToReadWhenScanned() = runTest {
        val id = saved(Book(isbn = emma, title = "Emma", status = ReadingStatus.WISHLIST))
        val vm = BatchScanViewModel(repo, "s1")
        vm.scanned(emma)
        advanceUntilIdle()
        assertEquals(ReadingStatus.TO_READ, repo.getBook(id)?.status)
        assertEquals("s1", repo.getBook(id)?.shelfId)
        vm.undo(emma)
        advanceUntilIdle()
        assertEquals(ReadingStatus.WISHLIST, repo.getBook(id)?.status)
        assertNull(repo.getBook(id)?.shelfId)
    }

    @Test
    fun notFoundOffersAddingByHand() = runTest {
        val vm = BatchScanViewModel(repo, "s1")
        vm.scanned(unknown)
        advanceUntilIdle()
        assertEquals(ScanResult.NotFound, result(vm, unknown))
        assertEquals(0, all().size)
    }

    @Test
    fun addedByHandLandsOnTheShelf() = runTest {
        saved(Book(isbn = stoner, title = "Stoner", shelfId = "s1", shelfPosition = 3.0))
        val vm = EditBookViewModel(repo, null, unknown, placeOnShelf = "s1")
        advanceUntilIdle()
        vm.update { it.copy(title = "By hand") }
        vm.save()
        advanceUntilIdle()
        val id = assertNotNull(vm.state.value.savedId)
        assertEquals("s1", repo.getBook(id)?.shelfId)
        assertEquals(4.0, repo.getBook(id)?.shelfPosition)
    }

    @Test
    fun withoutAShelfScanningIsUnchanged() = runTest {
        val vm = BatchScanViewModel(repo)
        vm.setStatus(ReadingStatus.WISHLIST)
        vm.scanned(emma)
        advanceUntilIdle()
        val book = all().single()
        assertEquals(ReadingStatus.WISHLIST, book.status)
        assertNull(book.shelfId)
        assertIs<ScanResult.Added>(result(vm, emma))
    }
}
