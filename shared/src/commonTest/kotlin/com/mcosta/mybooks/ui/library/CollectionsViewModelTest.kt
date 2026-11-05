package com.mcosta.mybooks.ui.library

import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.ChallengeRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.FakeChallengeDao
import com.mcosta.mybooks.data.PinnedTags
import com.mcosta.mybooks.data.SaveResult
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.BookFilter
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.ui.detail.BookDetailViewModel
import com.mcosta.mybooks.ui.home.HomeViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tags, series and loans on the shelf screen, the home screen and the book page. */
class CollectionsViewModelTest : MainDispatcherTest() {
    private val repo = BookRepository(FakeBookDao(), object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
    })
    private val pinned = PinnedTags(null)

    private suspend fun saved(book: Book) = (repo.save(book) as SaveResult.Saved).id

    private suspend fun sampleLibrary() {
        saved(Book(title = "Dune", status = ReadingStatus.READ, tags = listOf("sci-fi", "favourites"), series = "Dune", seriesNumber = "1"))
        saved(Book(title = "Children of Dune", status = ReadingStatus.TO_READ, tags = listOf("Sci-fi"), series = "Dune", seriesNumber = "3"))
        saved(Book(title = "Dune Messiah", status = ReadingStatus.READ, series = "Dune", seriesNumber = "2", lentTo = "Ana", lentOn = 5))
        saved(Book(title = "Emma", status = ReadingStatus.READ, tags = listOf("classics", "favourites")))
    }

    @Test
    fun aShelfOffersItsTagsAndAChipNarrowsIt() = runTest {
        sampleLibrary()
        val vm = LibraryViewModel(repo, section = ReadingStatus.READ)
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        assertEquals(listOf("favourites", "classics", "sci-fi"), vm.state.value.tags)
        vm.chooseTag("FAVOURITES")
        assertEquals(listOf("Dune", "Emma"), vm.state.value.books.map { it.title })
        assertEquals("favourites", vm.state.value.tag)
        vm.chooseTag(null)
        assertEquals(3, vm.state.value.books.size)
    }

    @Test
    fun aChipForATagNotOnTheNewTabIsLetGo() = runTest {
        sampleLibrary()
        val vm = LibraryViewModel(repo, section = ReadingStatus.READ)
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        vm.chooseTag("classics")
        vm.selectTab(ReadingStatus.TO_READ)
        assertNull(vm.state.value.tag)
        assertEquals(listOf("Children of Dune"), vm.state.value.books.map { it.title })
    }

    @Test
    fun aTagShowsItsBooksFromEveryShelfAndCanBePinned() = runTest {
        sampleLibrary()
        val vm = LibraryViewModel(repo, filter = BookFilter.Tag("sci-fi"), pinnedTags = pinned)
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        assertEquals(listOf("Children of Dune", "Dune"), vm.state.value.books.map { it.title }.sorted())
        assertEquals(listOf<String>(), vm.state.value.tags)
        assertFalse(vm.state.value.pinned)
        vm.setPinned(true)
        assertTrue(vm.state.value.pinned)
        assertEquals(listOf("sci-fi"), pinned.tags.value)
    }

    @Test
    fun aSeriesIsInItsOwnOrder() = runTest {
        sampleLibrary()
        val vm = LibraryViewModel(repo, filter = BookFilter.Series("dune"))
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        assertEquals(listOf("Dune", "Dune Messiah", "Children of Dune"), vm.state.value.books.map { it.title })
        assertFalse(vm.state.value.sortable)
    }

    @Test
    fun lentBooksAreTogether() = runTest {
        sampleLibrary()
        val vm = LibraryViewModel(repo, filter = BookFilter.Lent)
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        assertEquals(listOf("Dune Messiah"), vm.state.value.books.map { it.title })
    }

    @Test
    fun homeShowsPinnedTagsAndTheBooksLentOut() = runTest {
        sampleLibrary()
        pinned.setPinned("favourites", true)
        pinned.setPinned("audiobooks", true)
        val vm = HomeViewModel(repo, ChallengeRepository(FakeChallengeDao()), pinnedTags = pinned, today = { LocalDate.parse("2026-11-04") })
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        assertEquals(listOf("favourites" to 2, "audiobooks" to 0), vm.state.value.tags.map { it.name to it.count })
        assertEquals("Lent out" to 1, vm.state.value.lent?.let { it.name to it.count })
        pinned.setPinned("favourites", false)
        assertEquals(listOf("audiobooks"), vm.state.value.tags.map { it.name })
    }

    @Test
    fun homeHasNoLentRowWhenEveryBookIsHome() = runTest {
        saved(Book(title = "Dune"))
        val vm = HomeViewModel(repo, ChallengeRepository(FakeChallengeDao()), today = { LocalDate.parse("2026-11-04") })
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        assertNull(vm.state.value.lent)
    }

    @Test
    fun theBookPageAddsAndRemovesTagsLendsAndListsItsSeries() = runTest {
        sampleLibrary()
        val id = repo.findByTitleAndAuthors("Children of Dune", "")!!.id
        val vm = BookDetailViewModel(repo, id, backgroundScope)
        backgroundScope.launch(mainDispatcher) { vm.seriesBooks.collect {} }
        backgroundScope.launch(mainDispatcher) { vm.allTags.collect {} }
        assertEquals(listOf("Dune", "Dune Messiah", "Children of Dune"), vm.seriesBooks.value.map { it.title })
        // The first spelling found (books A to Z) names a tag written two ways.
        assertEquals(listOf("favourites", "Sci-fi", "classics"), vm.allTags.value)

        vm.addTag("favourites")
        vm.removeTag("SCI-FI")
        assertEquals(listOf("favourites"), repo.getBook(id)!!.tags)

        vm.lend("Rui", 9)
        assertEquals("Rui" to 9L, repo.getBook(id)!!.let { it.lentTo to it.lentOn })
        vm.returned()
        assertNull(repo.getBook(id)!!.lentTo)
    }
}

/** Quotes on the book page: added, changed, deleted and put back. */
class BookQuotesViewModelTest : MainDispatcherTest() {
    private val quoteDao = com.mcosta.mybooks.data.FakeQuoteDao()
    private val repo = BookRepository(FakeBookDao(), object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
    }, quotes = quoteDao)

    @Test
    fun quotesOnTheBookPage() = runTest {
        val id = (repo.save(Book(title = "Dune")) as SaveResult.Saved).id
        val vm = BookDetailViewModel(repo, id, backgroundScope, quoteRepo = com.mcosta.mybooks.data.QuoteRepository(quoteDao))
        backgroundScope.launch(mainDispatcher) { vm.quotes.collect {} }

        vm.addQuote("Fear is the mind-killer.", " 8 ")
        vm.addQuote("The spice must flow.", "")
        testScheduler.runCurrent()
        assertEquals(listOf("Fear is the mind-killer." to 8, "The spice must flow." to null), vm.quotes.value.map { it.text to it.page })

        val fear = vm.quotes.value.first()
        vm.updateQuote(fear, "Fear is the little-death.", "9")
        testScheduler.runCurrent()
        assertEquals("Fear is the little-death." to 9, vm.quotes.value.first().let { it.text to it.page })

        val changed = vm.quotes.value.first()
        vm.deleteQuote(changed)
        assertEquals(listOf("The spice must flow."), vm.quotes.value.map { it.text })
        vm.restoreQuote(changed)
        assertEquals(listOf("Fear is the little-death." to 9, "The spice must flow." to null), vm.quotes.value.map { it.text to it.page })
    }
}
