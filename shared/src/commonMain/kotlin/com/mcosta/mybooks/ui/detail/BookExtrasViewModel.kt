package com.mcosta.mybooks.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.authors.AuthorBook
import com.mcosta.mybooks.data.authors.AuthorBooksSource
import com.mcosta.mybooks.data.authors.catalogTitleKey
import com.mcosta.mybooks.data.authors.findInLibrary
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.social.SocialException
import com.mcosta.mybooks.social.SocialServices
import com.mcosta.mybooks.social.model.BOOK_REVIEWS_PAGE
import com.mcosta.mybooks.social.model.BookReview
import com.mcosta.mybooks.social.model.BookScore
import com.mcosta.mybooks.social.model.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The book page's community reviews section. */
sealed interface CommunityState {
    /** The community isn't set up in this app: no section. */
    data object Hidden : CommunityState
    data object SignedOut : CommunityState
    /** Readers are matched on the ISBN. */
    data object NoIsbn : CommunityState
    data object Loading : CommunityState
    data class Loaded(
        val score: BookScore,
        val reviews: List<BookReview>,
        val canLoadMore: Boolean,
        val loadingMore: Boolean = false,
    ) : CommunityState
    data class Failed(val message: String) : CommunityState
}

/** A book by the same author, and the library's copy if there is one. */
data class MoreByBook(val book: AuthorBook, val inLibrary: Book?)

/** "More by …": [books] is empty while [loading] or when none were found. */
data class MoreByState(val author: String, val books: List<MoreByBook> = emptyList(), val loading: Boolean = true)

/**
 * The book page's sections that come from elsewhere: a description when the book has none, the community's
 * scores and reviews, and more books by the same author.
 */
class BookExtrasViewModel(
    private val repo: BookRepository,
    private val id: Long,
    private val social: SocialServices?,
    private val authorBooks: AuthorBooksSource?,
) : ViewModel() {
    private val _community = MutableStateFlow<CommunityState>(if (social == null) CommunityState.Hidden else CommunityState.Loading)
    val community: StateFlow<CommunityState> = _community.asStateFlow()

    private val _moreBy = MutableStateFlow<MoreByState?>(null)
    val moreBy: StateFlow<MoreByState?> = _moreBy.asStateFlow()

    private var isbn: String? = null

    init {
        viewModelScope.launch {
            val book = repo.observeBook(id).filterNotNull().first()
            isbn = book.isbn
            launch { repo.fillDescription(id) }
            launch { loadCommunity() }
            launch { loadMoreBy(book) }
        }
    }

    fun retryCommunity() {
        viewModelScope.launch { loadCommunity() }
    }

    private suspend fun loadCommunity() {
        val social = social ?: return
        val isbn = isbn
        _community.value = when {
            social.auth.session.value !is SessionState.SignedIn -> CommunityState.SignedOut
            isbn == null -> CommunityState.NoIsbn
            else -> CommunityState.Loading
        }
        if (_community.value != CommunityState.Loading || isbn == null) return
        _community.value = try {
            val score = social.activity.bookScore(isbn)
            val reviews = social.activity.bookReviews(isbn)
            CommunityState.Loaded(score, reviews, canLoadMore = reviews.size == BOOK_REVIEWS_PAGE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocialException) {
            CommunityState.Failed("Couldn’t load the community’s reviews")
        }
    }

    /** The next page of reviews ("Show more"). */
    fun moreReviews() {
        val loaded = _community.value as? CommunityState.Loaded ?: return
        val isbn = isbn ?: return
        if (!loaded.canLoadMore || loaded.loadingMore) return
        _community.value = loaded.copy(loadingMore = true)
        viewModelScope.launch {
            _community.value = try {
                val next = social!!.activity.bookReviews(isbn, skip = loaded.reviews.size)
                loaded.copy(reviews = loaded.reviews + next, canLoadMore = next.size == BOOK_REVIEWS_PAGE, loadingMore = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SocialException) {
                loaded.copy(loadingMore = false)
            }
        }
    }

    private suspend fun loadMoreBy(book: Book) {
        val author = book.authors.substringBefore(',').trim()
        val source = authorBooks ?: return
        if (author.isEmpty()) return
        _moreBy.value = MoreByState(author)
        val thisTitle = catalogTitleKey(book.title)
        val found = source.booksBy(author)
            .filter { (it.isbn == null || it.isbn != book.isbn) && catalogTitleKey(it.title) != thisTitle }
        // Follows the library, so a book added from its own page shows as in the library on return.
        repo.observeBooks(null, "").collect { library ->
            _moreBy.value = MoreByState(author, found.map { MoreByBook(it, findInLibrary(it, library)) }, loading = false)
        }
    }
}
