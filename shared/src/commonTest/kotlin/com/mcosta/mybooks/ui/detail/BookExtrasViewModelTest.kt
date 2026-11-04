package com.mcosta.mybooks.ui.detail

import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.SaveResult
import com.mcosta.mybooks.data.authors.AuthorBook
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.social.FakeSocial
import com.mcosta.mybooks.social.SocialException
import com.mcosta.mybooks.social.model.BookReview
import com.mcosta.mybooks.social.model.Person
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class BookExtrasViewModelTest : MainDispatcherTest() {
    private val repo = BookRepository(FakeBookDao(), object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.Found(Book(title = "x", description = "Looked up."))
    })
    private val social = FakeSocial()
    private val isbn = "9789722113922"

    private suspend fun saved(book: Book) = (repo.save(book) as SaveResult.Saved).id
    private fun review(name: String, rating: Int?, text: String = "") =
        BookReview(Person(name, name, name.uppercase(), null), rating, text, Instant.parse("2026-11-01T10:00:00Z"))

    private fun signedIn() = social.signInAs(social.addUser("ana"))

    @Test
    fun descriptionIsFilledInWhenMissing() = runTest {
        val id = saved(Book(title = "Ensaio", isbn = isbn))
        BookExtrasViewModel(repo, id, null, null)
        advanceUntilIdle()
        assertEquals("Looked up.", repo.getBook(id)!!.description)
    }

    @Test
    fun communityScoresAndReviews() = runTest {
        signedIn()
        social.bookReviewsByIsbn[isbn] = listOf(review("bruno", 4, "Loved it"), review("carla", 5))
        val id = saved(Book(title = "Ensaio", isbn = isbn))
        val vm = BookExtrasViewModel(repo, id, social.services, null)
        advanceUntilIdle()
        val loaded = assertIs<CommunityState.Loaded>(vm.community.value)
        assertEquals(2, loaded.score.ratings)
        assertEquals(4.5, loaded.score.average)
        assertEquals(listOf(1, 1, 0, 0, 0), loaded.score.perScore)
        assertEquals(1, loaded.score.reviews)
        assertEquals(listOf("bruno", "carla"), loaded.reviews.map { it.person.username })
        assertEquals(false, loaded.canLoadMore)
    }

    @Test
    fun reviewsPageWithShowMore() = runTest {
        signedIn()
        social.bookReviewsByIsbn[isbn] = List(13) { review("r$it", 3, "text $it") }
        val vm = BookExtrasViewModel(repo, saved(Book(title = "Ensaio", isbn = isbn)), social.services, null)
        advanceUntilIdle()
        assertEquals(true, (vm.community.value as CommunityState.Loaded).canLoadMore)
        vm.moreReviews()
        advanceUntilIdle()
        val all = vm.community.value as CommunityState.Loaded
        assertEquals(13, all.reviews.size)
        assertEquals(false, all.canLoadMore)
    }

    @Test
    fun signedOutNoIsbnAndNoCommunity() = runTest {
        val withIsbn = saved(Book(title = "Ensaio", isbn = isbn))
        val noIsbn = saved(Book(title = "Notes"))
        val signedOut = BookExtrasViewModel(repo, withIsbn, social.services, null)
        val hidden = BookExtrasViewModel(repo, withIsbn, null, null)
        advanceUntilIdle()
        assertEquals(CommunityState.SignedOut, signedOut.community.value)
        assertEquals(CommunityState.Hidden, hidden.community.value)
        signedIn()
        val missing = BookExtrasViewModel(repo, noIsbn, social.services, null)
        advanceUntilIdle()
        assertEquals(CommunityState.NoIsbn, missing.community.value)
    }

    @Test
    fun failureCanBeRetried() = runTest {
        signedIn()
        social.offline = true
        val vm = BookExtrasViewModel(repo, saved(Book(title = "Ensaio", isbn = isbn)), social.services, null)
        advanceUntilIdle()
        assertIs<CommunityState.Failed>(vm.community.value)
        social.offline = false
        vm.retryCommunity()
        advanceUntilIdle()
        assertIs<CommunityState.Loaded>(vm.community.value)
    }

    @Test
    fun moreByTheAuthorMarksLibraryBooksAndFollowsTheLibrary() = runTest {
        val id = saved(Book(title = "Ensaio sobre a Cegueira", authors = "José Saramago, Translator", isbn = isbn))
        saved(Book(title = "Memorial do Convento", authors = "Jose Saramago", status = ReadingStatus.READ))
        val found = listOf(
            AuthorBook("Ensaio sobre a Cegueira: romance", "José Saramago", 1995, null, "9789720000000", null, null, null),  // this book
            AuthorBook("Memorial do Convento", "José Saramago", 1982, "Caminho", "9789720046857", null, null, null),
            AuthorBook("Caim", "José Saramago", 2009, "Caminho", "9789722120005", "https://c/caim.jpg", "Sobre Caim.", 180),
        )
        val asked = mutableListOf<String>()
        val vm = BookExtrasViewModel(repo, id, null) { author -> asked += author; found }
        advanceUntilIdle()
        val more = vm.moreBy.value!!
        assertEquals("José Saramago", more.author)
        assertEquals(listOf("José Saramago"), asked)
        assertEquals(listOf("Memorial do Convento", "Caim"), more.books.map { it.book.title })
        assertEquals(ReadingStatus.READ, more.books[0].inLibrary?.status)
        assertEquals(null, more.books[1].inLibrary)

        // Added from its own page: the row follows the library.
        repo.save(Book(title = "Caim", authors = "José Saramago", isbn = "9789722120005", status = ReadingStatus.WISHLIST))
        advanceUntilIdle()
        assertEquals(ReadingStatus.WISHLIST, vm.moreBy.value!!.books[1].inLibrary?.status)
    }
}
