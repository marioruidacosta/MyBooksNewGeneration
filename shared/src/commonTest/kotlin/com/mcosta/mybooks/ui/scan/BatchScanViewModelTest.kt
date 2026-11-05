package com.mcosta.mybooks.ui.scan

import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatchScanViewModelTest : MainDispatcherTest() {
    private val dune = "9780441013593"
    private val emma = "9780141439587"
    private val unknown = "9789720000000"
    private val offline = "9789720046857"
    private var online = false
    private val repo = BookRepository(FakeBookDao(), object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = when (isbn) {
            dune -> LookupResult.Found(Book(title = "Dune", authors = "Frank Herbert", coverUrl = "https://c/dune.jpg"))
            emma -> LookupResult.Found(Book(title = "Emma", authors = "Jane Austen"))
            offline -> if (online) LookupResult.Found(Book(title = "Solaris", authors = "Stanisław Lem")) else LookupResult.Error(RuntimeException("offline"))
            else -> LookupResult.NotFound
        }
    })

    private fun result(vm: BatchScanViewModel, isbn: String) = vm.state.value.items.first { it.isbn == isbn }.result

    @Test
    fun eachScanIsAddedToTheChosenShelfNewestFirst() = runTest {
        val vm = BatchScanViewModel(repo)
        vm.scanned(dune)
        vm.setStatus(ReadingStatus.WISHLIST)
        vm.scanned(emma)
        assertEquals(listOf(emma, dune), vm.state.value.items.map { it.isbn })
        assertEquals(ReadingStatus.TO_READ, repo.findByIsbn(dune)!!.status)
        assertEquals(ReadingStatus.WISHLIST, repo.findByIsbn(emma)!!.status)
        assertEquals("https://c/dune.jpg", repo.findByIsbn(dune)!!.coverUrl)
        assertEquals(2, vm.state.value.added)
    }

    @Test
    fun aBookAlreadyInTheLibraryIsNotAddedAgain() = runTest {
        repo.save(Book(title = "Dune", isbn = dune, status = ReadingStatus.READ))
        val vm = BatchScanViewModel(repo)
        vm.scanned(dune)
        val r = assertIs<ScanResult.AlreadyHave>(result(vm, dune))
        assertEquals(ReadingStatus.READ, r.book.status)
        assertEquals(1, repo.observeBooks(null, "").first().size)
    }

    @Test
    fun scanningTheSameBarcodeTwiceIsIgnored() = runTest {
        val vm = BatchScanViewModel(repo)
        assertTrue(vm.scanned(dune))
        assertFalse(vm.scanned(dune))
        assertEquals(1, vm.state.value.items.size)
    }

    @Test
    fun undoTakesTheBookBackOut() = runTest {
        val vm = BatchScanViewModel(repo)
        vm.scanned(dune)
        vm.undo(dune)
        assertIs<ScanResult.Undone>(result(vm, dune))
        assertNull(repo.findByIsbn(dune))
        assertEquals(0, vm.state.value.added)
    }

    @Test
    fun unknownBooksCanBeAddedByHandAndOfflineOnesRetried() = runTest {
        val vm = BatchScanViewModel(repo)
        vm.scanned(unknown)
        vm.scanned(offline)
        assertEquals(ScanResult.NotFound, result(vm, unknown))
        assertIs<ScanResult.Failed>(result(vm, offline))
        assertNull(repo.findByIsbn(offline))
        online = true
        vm.retry(offline)
        assertEquals("Solaris", assertIs<ScanResult.Added>(result(vm, offline)).book.title)
    }
}
