@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.mcosta.mybooks.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.FakeShareStateDao
import com.mcosta.mybooks.share.ShareTracker
import com.mcosta.mybooks.data.SaveResult
import com.mcosta.mybooks.data.authors.AuthorInfo
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.reflect.KClass

class BookDetailViewModelTest : MainDispatcherTest() {
    private val dao = FakeBookDao()
    private val repo = BookRepository(dao, object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
    }, clock = { 5000L })

    private suspend fun newBook(notes: String = "") =
        (repo.save(Book(title = "Dune", notes = notes)) as SaveResult.Saved).id

    /** Creates the ViewModel inside a store so the test can clear it like navigation does. */
    private fun TestScope.inStore(id: Long, saveScope: CoroutineScope = backgroundScope): Pair<BookDetailViewModel, ViewModelStore> {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
                BookDetailViewModel(repo, id, saveScope) as T
        }
        return ViewModelProvider.create(store, factory)[BookDetailViewModel::class] to store
    }

    @Test
    fun loadsBookAndNotesDraft() = runTest {
        val id = newBook(notes = "first thoughts")
        val (vm, _) = inStore(id)
        assertEquals("Dune", vm.book.value?.title)
        assertEquals("first thoughts", vm.notesDraft.value)
    }

    @Test
    fun setStatusReadSetsDateRead() = runTest {
        val id = newBook()
        val (vm, _) = inStore(id)
        vm.setStatus(ReadingStatus.READ)
        assertEquals(ReadingStatus.READ, vm.book.value?.status)
        assertEquals(5000L, vm.book.value?.dateRead)
    }

    @Test
    fun rateAndClear() = runTest {
        val id = newBook()
        val (vm, _) = inStore(id)
        vm.rate(4)
        assertEquals(4, vm.book.value?.rating)
        vm.rate(null)
        assertNull(vm.book.value?.rating)
    }

    @Test
    fun saveNotesPersists() = runTest {
        val id = newBook()
        val (vm, _) = inStore(id, saveScope = this)
        vm.editNotes("brilliant")
        vm.saveNotes()
        advanceUntilIdle()
        assertEquals("brilliant", dao.getById(id)?.notes)
    }

    @Test
    fun notesSavedOnCleared() = runTest {
        val id = newBook()
        val (vm, store) = inStore(id, saveScope = this)
        vm.editNotes("typed then pressed back")
        store.clear()
        advanceUntilIdle()
        assertEquals("typed then pressed back", dao.getById(id)?.notes)
    }

    @Test
    fun notesChangedElsewhereRefreshDraftAndAreNotOverwritten() = runTest {
        val id = newBook(notes = "a")
        val (vm, store) = inStore(id, saveScope = this)
        repo.setNotes(id, "b") // e.g. edited on the Edit screen
        assertEquals("b", vm.notesDraft.value)
        store.clear()
        advanceUntilIdle()
        assertEquals("b", dao.getById(id)?.notes)
    }

    @Test
    fun unsavedDraftIsKeptWhenBookChangesElsewhere() = runTest {
        val id = newBook(notes = "a")
        val (vm, _) = inStore(id, saveScope = this)
        vm.editNotes("typing…")
        vm.rate(3)
        assertEquals("typing…", vm.notesDraft.value)
    }

    @Test
    fun updateProgressByPages() = runTest {
        val id = (repo.save(Book(title = "Dune", pageCount = 200, status = ReadingStatus.READING)) as SaveResult.Saved).id
        val (vm, _) = inStore(id)
        vm.updateProgress(" 50 ", byPages = true)
        assertEquals(50, vm.book.value?.currentPage)
        assertEquals(25, vm.book.value?.progressPercent)
        assertNull(vm.progressError.value)
    }

    @Test
    fun updateProgressByPercent() = runTest {
        val id = (repo.save(Book(title = "Dune", status = ReadingStatus.READING)) as SaveResult.Saved).id
        val (vm, _) = inStore(id)
        vm.updateProgress("45", byPages = false)
        assertEquals(45, vm.book.value?.progressPercent)
    }

    @Test
    fun invalidProgressShowsErrorAndChangesNothing() = runTest {
        val id = (repo.save(Book(title = "Dune", status = ReadingStatus.READING)) as SaveResult.Saved).id
        val (vm, _) = inStore(id)
        vm.updateProgress("150", byPages = false)
        assertEquals("Enter a percentage from 0 to 100", vm.progressError.value)
        vm.updateProgress("abc", byPages = true)
        assertEquals("Enter a page number", vm.progressError.value)
        assertNull(vm.book.value?.progressPercent)
        vm.updateProgress("10", byPages = true)
        assertNull(vm.progressError.value)
    }

    @Test
    fun setCoverFromPhoto() = runTest {
        val id = newBook()
        val (vm, _) = inStore(id)
        vm.setCover("cover:abc.jpg")
        assertEquals("cover:abc.jpg", vm.book.value?.coverUrl)
    }

    @Test
    fun setDateStarted() = runTest {
        val id = (repo.save(Book(title = "Dune", status = ReadingStatus.READING)) as SaveResult.Saved).id
        val (vm, _) = inStore(id)
        vm.setDateStarted(123L)
        assertEquals(123L, vm.book.value?.dateStarted)
    }

    @Test
    fun deleteRemovesBook() = runTest {
        val id = newBook()
        val (vm, _) = inStore(id)
        vm.delete()
        advanceUntilIdle()
        assertTrue(vm.deleted.value)
        assertNull(dao.getById(id))
    }

    @Test
    fun clearingAfterDeleteDoesNotRecreateBook() = runTest {
        val id = newBook()
        val (vm, store) = inStore(id, saveScope = this)
        vm.editNotes("x")
        vm.delete()
        store.clear()
        advanceUntilIdle()
        assertNull(dao.getById(id))
        assertNotNull(vm)
    }

    @Test
    fun reviewDraftSavedOnLeave() = runTest {
        val id = newBook()
        val (vm, store) = inStore(id, saveScope = this)
        vm.editReview("Loved it")
        store.clear()
        advanceUntilIdle()
        assertEquals("Loved it", repo.getBook(id)?.review)
    }

    @Test
    fun reviewCappedAt2000() = runTest {
        val id = newBook()
        val (vm, _) = inStore(id)
        vm.editReview("x".repeat(2500))
        assertEquals(2000, vm.reviewDraft.value.length)
    }

    @Test
    fun excludeTogglesTracker() = runTest {
        val sharingRepo = BookRepository(
            dao, object : BookLookupService { override suspend fun lookup(isbn: String) = LookupResult.NotFound },
            shareTracker = ShareTracker(FakeShareStateDao(), activeOwner = { "ana" }, onPending = {}),
        )
        val id = (sharingRepo.save(Book(title = "Emma")) as SaveResult.Saved).id
        val vm = BookDetailViewModel(sharingRepo, id, backgroundScope)
        assertEquals(false, vm.shareExcluded.value)
        vm.setShareExcluded(true)
        assertEquals(true, vm.shareExcluded.value)
        vm.setShareExcluded(false)
        assertEquals(false, vm.shareExcluded.value)
    }

    @Test
    fun authorPanelShowsTheBiographyAndTheirOtherBooks() = runTest {
        val id = (repo.save(Book(title = "Ensaio sobre a Cegueira", authors = "José Saramago, Tradutor X", status = ReadingStatus.READ)) as SaveResult.Saved).id
        repo.save(Book(title = "Memorial do Convento", authors = "Jose Saramago", status = ReadingStatus.TO_READ))
        repo.save(Book(title = "Caim", authors = "José Saramago", status = ReadingStatus.READ))
        repo.save(Book(title = "Other", authors = "Someone Else"))
        val asked = mutableListOf<String>()
        val bio = AuthorInfo("José Saramago", "escritor português", "Foi um escritor.", null, null)
        val vm = BookDetailViewModel(repo, id, backgroundScope, authors = { name -> asked += name; bio })
        assertNull(vm.author.value)
        vm.loadAuthor()
        advanceUntilIdle()
        val panel = vm.author.value!!
        assertEquals("José Saramago", panel.name)
        assertEquals(bio, panel.info)
        assertEquals(listOf("Caim", "Memorial do Convento"), panel.otherBooks.map { it.title })   // read first, then A–Z
        assertEquals(false, panel.loading)
        vm.loadAuthor()
        advanceUntilIdle()
        assertEquals(listOf("José Saramago"), asked)                                              // loaded once
    }

    @Test
    fun authorPanelWithoutBiography() = runTest {
        val id = (repo.save(Book(title = "Anon", authors = "Jade Khoo")) as SaveResult.Saved).id
        val vm = BookDetailViewModel(repo, id, backgroundScope, authors = { null })
        vm.loadAuthor()
        advanceUntilIdle()
        assertEquals(AuthorPanel("Jade Khoo", info = null, otherBooks = emptyList(), loading = false), vm.author.value)
    }

    /** Undo after deleting a highlight puts it back where it was in the ebook, with its date. */
    @Test
    fun undoingAHighlightDeleteKeepsItsPlace() = runTest {
        val quotes = com.mcosta.mybooks.data.QuoteRepository(com.mcosta.mybooks.data.FakeQuoteDao(), clock = { 5L })
        val id = (repo.save(Book(title = "Emma")) as SaveResult.Saved).id
        quotes.addHighlight(id, "Badly done, Emma!", "{\"p\":3}")
        val vm = BookDetailViewModel(repo, id, backgroundScope, quoteRepo = quotes)
        val quote = quotes.observe(id).first().single()
        vm.deleteQuote(quote)
        vm.restoreQuote(quote)
        advanceUntilIdle()
        val back = quotes.observe(id).first().single()
        assertEquals(quote.copy(id = back.id), back)
    }
}
