package com.mcosta.mybooks.ui.library

import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.SaveResult
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.data.Preferences
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.Test

class LibraryViewModelTest : MainDispatcherTest() {
    private val repo = BookRepository(FakeBookDao(), object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
    })

    @Test
    fun tabAndQueryFilterBooks() = runTest {
        repo.save(Book(title = "Dune", authors = "Frank Herbert", status = ReadingStatus.READ))
        repo.save(Book(title = "Emma", authors = "Jane Austen", status = ReadingStatus.WISHLIST))
        repo.save(Book(title = "Persuasion", authors = "Jane Austen", status = ReadingStatus.READ))
        val vm = LibraryViewModel(repo)
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }

        assertEquals(listOf("Dune", "Emma", "Persuasion"), vm.state.value.books.map { it.title })

        vm.selectTab(ReadingStatus.READ)
        assertEquals(listOf("Dune", "Persuasion"), vm.state.value.books.map { it.title })

        vm.setQuery("austen")
        assertEquals(listOf("Persuasion"), vm.state.value.books.map { it.title })
        assertEquals(ReadingStatus.READ, vm.state.value.tab)
        assertEquals("austen", vm.state.value.query)

        vm.selectTab(null)
        assertEquals(listOf("Emma", "Persuasion"), vm.state.value.books.map { it.title })
    }

    private class MemoryPreferences : Preferences {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String) { values[key] = value }
    }

    @Test
    fun eachTabKeepsItsOwnRememberedSort() = runTest {
        repo.save(Book(title = "Dune", authors = "Frank Herbert", publisher = "Ace", status = ReadingStatus.READ, rating = 3))
        repo.save(Book(title = "Emma", authors = "Jane Austen", publisher = "Penguin", status = ReadingStatus.WISHLIST))
        repo.save(Book(title = "Persuasion", authors = "Jane Austen", publisher = "Bantam", status = ReadingStatus.READ, rating = 5))
        val prefs = MemoryPreferences()
        val vm = LibraryViewModel(repo, prefs)
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }

        assertEquals(listOf(LibrarySort.TITLE, LibrarySort.AUTHOR, LibrarySort.PUBLISHER, LibrarySort.DATE_ADDED), vm.state.value.sorts)
        vm.setSort(LibrarySort.AUTHOR)
        assertEquals(listOf("Emma", "Persuasion", "Dune"), vm.state.value.books.map { it.title })
        vm.setDescending(true)
        assertEquals(listOf("Dune", "Emma", "Persuasion"), vm.state.value.books.map { it.title })
        vm.setSort(LibrarySort.SCORE)                                  // not offered outside Read: ignored
        assertEquals(SortChoice(LibrarySort.AUTHOR, descending = true), vm.state.value.sort)

        vm.selectTab(ReadingStatus.READ)
        assertEquals(SortChoice(), vm.state.value.sort)                // each tab has its own
        assertEquals(LibrarySort.entries, vm.state.value.sorts)
        vm.setSort(LibrarySort.SCORE)                                  // score starts highest first
        assertEquals(listOf("Persuasion", "Dune"), vm.state.value.books.map { it.title })
        vm.setDescending(false)
        assertEquals(listOf("Dune", "Persuasion"), vm.state.value.books.map { it.title })

        vm.selectTab(ReadingStatus.WISHLIST)
        assertEquals(SortChoice(), vm.state.value.sort)
        vm.setSort(LibrarySort.PUBLISHER)

        // Remembered after the app is closed.
        val again = LibraryViewModel(repo, prefs)
        backgroundScope.launch(mainDispatcher) { again.state.collect {} }
        assertEquals(SortChoice(LibrarySort.AUTHOR, descending = true), again.state.value.sort)
        again.selectTab(ReadingStatus.READ)
        assertEquals(SortChoice(LibrarySort.SCORE, descending = false), again.state.value.sort)
        again.selectTab(ReadingStatus.WISHLIST)
        assertEquals(SortChoice(LibrarySort.PUBLISHER), again.state.value.sort)
        again.selectTab(ReadingStatus.TO_READ)
        assertEquals(SortChoice(), again.state.value.sort)
    }

    @Test
    fun publisherSortWithSearch() = runTest {
        repo.save(Book(title = "Persuasion", authors = "Jane Austen", publisher = "Penguin"))
        repo.save(Book(title = "Emma", authors = "Jane Austen", publisher = "Bantam"))
        val vm = LibraryViewModel(repo)
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        vm.setSort(LibrarySort.PUBLISHER)
        vm.setQuery("austen")
        assertEquals(listOf("Emma", "Persuasion"), vm.state.value.books.map { it.title })
    }

    @Test
    fun dateAddedIsOfferedOnEveryShelfAndStartsNewestFirst() = runTest {
        val vm = LibraryViewModel(repo, MemoryPreferences(), ReadingStatus.TO_READ)
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        assertEquals(listOf(LibrarySort.TITLE, LibrarySort.AUTHOR, LibrarySort.PUBLISHER, LibrarySort.DATE_ADDED), vm.state.value.sorts)
        vm.setSort(LibrarySort.DATE_ADDED)
        assertEquals(SortChoice(LibrarySort.DATE_ADDED, descending = true), vm.state.value.sort)
    }

    @Test
    fun swipeRightMarksReadOrBackToToRead() {
        assertEquals(ReadingStatus.READ, swipeTarget(Book(title = "a", status = ReadingStatus.TO_READ)))
        assertEquals(ReadingStatus.READ, swipeTarget(Book(title = "a", status = ReadingStatus.READING)))
        assertEquals(ReadingStatus.READ, swipeTarget(Book(title = "a", status = ReadingStatus.WISHLIST)))
        assertEquals(ReadingStatus.TO_READ, swipeTarget(Book(title = "a", status = ReadingStatus.READ)))
    }

    @Test
    fun swipedStatusCanBeUndoneWithItsDates() = runTest {
        val id = (repo.save(Book(title = "Dune", status = ReadingStatus.READING, dateStarted = 500, progressPercent = 40)) as SaveResult.Saved).id
        val before = repo.getBook(id)!!
        val vm = LibraryViewModel(repo)
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        vm.swipeStatus(before)
        assertEquals(ReadingStatus.READ, repo.getBook(id)!!.status)
        vm.undoStatus(before)
        val back = repo.getBook(id)!!
        assertEquals(ReadingStatus.READING, back.status)
        assertEquals(500L, back.dateStarted)
        assertEquals(40, back.progressPercent)
        assertEquals(null, back.dateRead)
    }

    @Test
    fun swipeLeftHidesThenDeletesUnlessUndone() = runTest {
        val keep = (repo.save(Book(title = "Keep")) as SaveResult.Saved).id
        val gone = (repo.save(Book(title = "Gone")) as SaveResult.Saved).id
        val vm = LibraryViewModel(repo)
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        vm.hide(keep)
        vm.hide(gone)
        assertEquals(emptyList(), vm.state.value.books.map { it.title })
        vm.undoDelete(keep)
        vm.delete(gone)
        assertEquals(listOf("Keep"), vm.state.value.books.map { it.title })
        assertEquals(null, repo.getBook(gone))
        assertEquals("Keep", repo.getBook(keep)?.title)
    }

    @Test
    fun gridChoiceIsRememberedForEveryShelf() = runTest {
        val prefs = MemoryPreferences()
        val vm = LibraryViewModel(repo, prefs, ReadingStatus.TO_READ)
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        assertEquals(false, vm.state.value.grid)
        vm.setGrid(true)
        assertEquals(true, vm.state.value.grid)
        val other = LibraryViewModel(repo, prefs, ReadingStatus.READ)
        backgroundScope.launch(mainDispatcher) { other.state.collect {} }
        assertEquals(true, other.state.value.grid)
    }
}
