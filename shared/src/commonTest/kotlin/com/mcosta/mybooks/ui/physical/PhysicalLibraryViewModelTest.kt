package com.mcosta.mybooks.ui.physical

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.FakeEbookDao
import com.mcosta.mybooks.data.db.EbookEntity
import com.mcosta.mybooks.data.db.toEntity
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.EbookFormat
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.library.MemoryPreferences
import com.mcosta.mybooks.physical.Bookcase
import com.mcosta.mybooks.physical.BookcaseColumn
import com.mcosta.mybooks.physical.BookcaseStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhysicalLibraryViewModelTest : MainDispatcherTest() {
    private val dao = FakeBookDao()
    private val ebooks = FakeEbookDao()
    private val prefs = MemoryPreferences()
    private val store = BookcaseStore(prefs, { prefs.values.keys }, {})
    private val repo = BookRepository(
        dao,
        object : BookLookupService {
            override suspend fun lookup(isbn: String) = LookupResult.NotFound
        },
        bookcases = store,
        ebooks = ebooks,
    )
    private val window = Bookcase(
        "w", "By the window", "Living room",
        listOf(BookcaseColumn("c1", listOf("s1", "s2")), BookcaseColumn("c2", listOf("s3"))),
    )

    private suspend fun add(title: String, shelf: String? = null, position: Double? = null, status: ReadingStatus = ReadingStatus.READ): Long =
        dao.insert(Book(title = title, shelfId = shelf, shelfPosition = position, status = status).toEntity())

    private suspend fun book(id: Long) = assertNotNull(repo.getBook(id))

    private fun vm() = PhysicalLibraryViewModel(repo, store)

    /** The screen closes while [act]'s write waits on the disk; returns what was queued for sync afterwards. */
    private suspend fun TestScope.queuedAfterClosingDuring(act: (PhysicalLibraryViewModel) -> Unit): List<String> {
        store.save(window)
        val disk = CompletableDeferred<Unit>()
        val queued = mutableListOf<String>()
        val slow = BookcaseStore(prefs, { prefs.values.keys }, { queued += it }, { _, _ -> disk.await() })
        val screens = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
                PhysicalLibraryViewModel(repo, slow, backgroundScope) as T
        }
        val vm = ViewModelProvider.create(screens, factory)[PhysicalLibraryViewModel::class]
        advanceUntilIdle()
        act(vm)
        runCurrent()
        screens.clear()
        disk.complete(Unit)
        runCurrent()
        return queued
    }

    @Test
    fun deletingABookcaseFinishesAfterTheScreenCloses() = runTest {
        assertEquals(listOf("bookcase.w"), queuedAfterClosingDuring { it.removeBookcase("w") })
    }

    @Test
    fun savingABookcaseFinishesAfterTheScreenCloses() = runTest {
        assertEquals(listOf("bookcase.w"), queuedAfterClosingDuring { it.saveBookcase(window.copy(name = "Renamed")) })
    }

    @Test
    fun placeAppendsInOrder() = runTest {
        store.save(window)
        val a = add("A")
        val b = add("B")
        val vm = vm()
        advanceUntilIdle()
        vm.place(listOf(b, a), "s1")
        advanceUntilIdle()
        assertEquals(1.0, book(b).shelfPosition)
        assertEquals(2.0, book(a).shelfPosition)
        assertEquals(listOf(b, a), vm.booksOn("s1").map { it.id })
    }

    @Test
    fun placingOnAShelfWithUnorderedBooksStillGoesLast() = runTest {
        store.save(window)
        val old = add("Old", "s1", null)
        val new = add("New")
        val vm = vm()
        advanceUntilIdle()
        vm.place(listOf(new), "s1")
        advanceUntilIdle()
        assertEquals(listOf(old, new), vm.booksOn("s1").map { it.id })
    }

    @Test
    fun undoPutsBooksBack() = runTest {
        store.save(window)
        val free = add("Free")
        val moved = add("Moved", "s2", 7.0)
        val vm = vm()
        advanceUntilIdle()
        vm.place(listOf(free, moved), "s1")
        advanceUntilIdle()
        assertEquals("s1", book(moved).shelfId)
        assertNotNull(vm.state.value.undo).invoke()
        advanceUntilIdle()
        assertNull(book(free).shelfId)
        assertNull(book(free).shelfPosition)
        assertEquals("s2", book(moved).shelfId)
        assertEquals(7.0, book(moved).shelfPosition)
    }

    @Test
    fun movingToTheMiddleChangesOnlyThatBook() = runTest {
        store.save(window)
        val a = add("A", "s1", 1.0)
        val b = add("B", "s1", 2.0)
        val c = add("C", "s1", 3.0)
        val vm = vm()
        advanceUntilIdle()
        vm.moveBook(c, "s1", 1)
        advanceUntilIdle()
        assertEquals(listOf(a, c, b), vm.booksOn("s1").map { it.id })
        assertEquals(1.5, book(c).shelfPosition)
        assertEquals(1.0, book(a).shelfPosition)
        assertEquals(2.0, book(b).shelfPosition)
    }

    @Test
    fun movingBesideUnorderedBooksRenumbers() = runTest {
        store.save(window)
        val a = add("A", "s1", null)
        val b = add("B", "s1", null)
        val c = add("C", "s1", null)
        val vm = vm()
        advanceUntilIdle()
        vm.moveBook(c, "s1", 0)
        advanceUntilIdle()
        assertEquals(listOf(c, a, b), vm.booksOn("s1").map { it.id })
        assertTrue(listOf(a, b, c).all { dao.getById(it)?.shelfPosition != null })
    }

    @Test
    fun noGapLeftRenumbers() = runTest {
        store.save(window)
        val a = add("A", "s1", 1.0)
        val b = add("B", "s1", 1.0000001)
        val c = add("C", "s1", 2.0)
        val vm = vm()
        advanceUntilIdle()
        vm.moveBook(c, "s1", 1)
        advanceUntilIdle()
        assertEquals(listOf(a, c, b), vm.booksOn("s1").map { it.id })
    }

    @Test
    fun removingABookcaseFreesItsBooks() = runTest {
        store.save(window)
        val on = add("On", "s1", 1.0, ReadingStatus.READING)
        val vm = vm()
        advanceUntilIdle()
        vm.removeBookcase("w")
        advanceUntilIdle()
        assertTrue(store.bookcases.value.isEmpty())
        val freed = book(on)
        assertEquals("On", freed.title)
        assertEquals(ReadingStatus.READING, freed.status)
        assertNull(freed.shelfId)
        assertNull(freed.shelfPosition)
        assertEquals(listOf(on), vm.state.value.withoutPlace.map { it.id })
    }

    @Test
    fun removingAShelfFreesOnlyItsBooks() = runTest {
        store.save(window)
        val gone = add("Gone", "s1", 1.0)
        val stays = add("Stays", "s2", 1.0)
        val vm = vm()
        advanceUntilIdle()
        vm.removeShelf(window, "s1")
        advanceUntilIdle()
        assertNull(book(gone).shelfId)
        assertEquals("s2", book(stays).shelfId)
        assertEquals(listOf("s2"), store.bookcases.value.single().columns[0].shelves)
    }

    @Test
    fun removingAColumnFreesItsBooks() = runTest {
        store.save(window)
        val gone = add("Gone", "s3", 1.0)
        val stays = add("Stays", "s1", 1.0)
        val vm = vm()
        advanceUntilIdle()
        vm.removeColumn(window, 1)
        advanceUntilIdle()
        assertNull(book(gone).shelfId)
        assertEquals("s1", book(stays).shelfId)
        assertEquals(1, store.bookcases.value.single().columns.size)
    }

    @Test
    fun aBookOnARemovedShelfReadsAsWithoutAPlace() = runTest {
        store.save(window)
        val orphan = add("Orphan", "s1", 1.0)
        val vm = vm()
        advanceUntilIdle()
        store.pulled("bookcase.w", null)
        advanceUntilIdle()
        assertEquals(listOf(orphan), vm.state.value.withoutPlace.map { it.id })
        assertEquals("s1", book(orphan).shelfId, "only a removal on this device clears the field")
    }

    @Test
    fun ebooksAreLeftOutOfWithoutAPlace() = runTest {
        val ebook = add("Ebook")
        val paper = add("Paper")
        ebooks.upsert(EbookEntity(bookId = ebook, format = EbookFormat.EPUB, fileName = "a.epub", size = 1, dateAdded = 1))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf(paper), vm.state.value.withoutPlace.map { it.id })
    }

    @Test
    fun countOnSaysHowManyAreAffected() = runTest {
        store.save(window)
        add("A", "s1", 1.0)
        add("B", "s2", 1.0)
        add("C", "s3", 1.0)
        add("To buy", "s1", 2.0, ReadingStatus.WISHLIST)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(2, vm.countOn(listOf("s1", "s2")))
    }

    @Test
    fun renamingARoomUpdatesItsBookcases() = runTest {
        store.save(window)
        store.save(window.copy(id = "o", name = "Other", room = "Office", columns = listOf(BookcaseColumn("c9", listOf("s9")))))
        store.pulled("bookcase.n", """{"v":2,"name":"Newer","room":"Living room","columns":[{"id":"c","shelves":["s"]}]}""")
        val vm = vm()
        advanceUntilIdle()
        vm.renameRoom("living ROOM", "  Lounge ")
        advanceUntilIdle()
        assertEquals("Lounge", store.bookcases.value.first { it.id == "w" }.room)
        assertEquals("Office", store.bookcases.value.first { it.id == "o" }.room)
        assertEquals("Living room", store.bookcases.value.first { it.id == "n" }.room, "a read-only bookcase isn't rewritten")
    }

    @Test
    fun takingABookOffGoesToWithoutAPlace() = runTest {
        store.save(window)
        val on = add("On", "s1", 1.0)
        val vm = vm()
        advanceUntilIdle()
        vm.takeOff(on)
        advanceUntilIdle()
        assertNull(book(on).shelfId)
        assertEquals(listOf(on), vm.state.value.withoutPlace.map { it.id })
    }
}
