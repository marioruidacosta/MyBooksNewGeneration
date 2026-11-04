package com.mcosta.mybooks.ui.detail

import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.authors.AuthorBook
import com.mcosta.mybooks.data.authors.CatalogBooks
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogBookViewModelTest : MainDispatcherTest() {
    private val repo = BookRepository(FakeBookDao(), object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
    })
    private val caim = AuthorBook("Caim", "José Saramago", 2009, "Caminho", "9789722120005", "https://c/caim.jpg", "Sobre Caim.", 180)

    @Test
    fun notInTheLibraryOffersAddingAndWhereToBuy() = runTest {
        val vm = CatalogBookViewModel(repo, caim)
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        assertNull(vm.state.value.inLibrary)
        assertEquals(listOf("Bertrand", "Almedina", "Wook", "Fnac"), vm.state.value.stores.map { it.name })
        assertEquals("https://www.wook.pt/pesquisa?keyword=9789722120005", vm.state.value.stores[2].url)
    }

    @Test
    fun addedToToBuyKeepsWhereToBuyToReadDoesNot() = runTest {
        val vm = CatalogBookViewModel(repo, caim)
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        vm.add(ReadingStatus.WISHLIST)
        val added = vm.state.value.inLibrary!!
        assertEquals(ReadingStatus.WISHLIST, added.status)
        assertEquals("Sobre Caim.", added.description)
        assertEquals(2009, added.publishedYear)
        assertEquals(4, vm.state.value.stores.size)
        vm.add(ReadingStatus.TO_READ)                                    // already in the library: nothing added
        assertEquals(1, repo.bookCount())
        repo.setStatus(added.id, ReadingStatus.TO_READ)
        assertEquals(emptyList(), vm.state.value.stores)
    }

    @Test
    fun alreadyInTheLibraryByTitleAndAuthor() = runTest {
        repo.save(Book(title = "Caim: romance", authors = "Jose Saramago", status = ReadingStatus.READ))
        val vm = CatalogBookViewModel(repo, caim.copy(isbn = null))
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        assertEquals(ReadingStatus.READ, vm.state.value.inLibrary?.status)
        assertEquals(emptyList(), vm.state.value.stores)
    }

    @Test
    fun withoutIsbnTheShopsSearchByTitleAndAuthor() = runTest {
        val vm = CatalogBookViewModel(repo, caim.copy(isbn = null, authors = "José Saramago, Tradutor"))
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        assertEquals("https://www.wook.pt/pesquisa?keyword=Caim+Jos%C3%A9+Saramago", vm.state.value.stores[2].url)
    }

    @Test
    fun anUnknownBookHasNothing() = runTest {
        val vm = CatalogBookViewModel(repo, CatalogBooks().get("c9"))
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        assertEquals(true, vm.state.value.loaded)
        assertEquals(emptyList(), vm.state.value.stores)
        vm.add(ReadingStatus.TO_READ)
        assertEquals(0, repo.bookCount())
    }

    @Test
    fun catalogBooksAreKeptByKey() {
        val books = CatalogBooks()
        val key = books.put(caim)
        assertEquals(caim, books.get(key))
        assertEquals(null, books.get("other"))
        assertEquals(false, key == books.put(caim))
    }
}
