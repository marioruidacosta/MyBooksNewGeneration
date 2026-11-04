package com.mcosta.mybooks.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.SaveResult
import com.mcosta.mybooks.data.authors.AuthorBook
import com.mcosta.mybooks.data.authors.AuthorBooksSource
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.domain.searchKey
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
        val library = repo.observeBooks(null, "").first()
        val thisTitle = titleKey(book.title)
        val found = source.booksBy(author)
            .filter { (it.isbn == null || it.isbn != book.isbn) && titleKey(it.title) != thisTitle }
            .map { MoreByBook(it, inLibrary(it, library)) }
        _moreBy.value = MoreByState(author, found, loading = false)
    }

    /** Adds a book from "More by …" to To read or To buy. */
    fun add(book: AuthorBook, status: ReadingStatus) {
        viewModelScope.launch {
            val result = repo.save(
                Book(
                    title = book.title, authors = book.authors, isbn = book.isbn, publisher = book.publisher,
                    publishedYear = book.year, pageCount = book.pageCount, coverUrl = book.coverUrl,
                    description = book.description, status = status,
                ),
            )
            val saved = when (result) {
                is SaveResult.Saved -> repo.getBook(result.id)
                SaveResult.DuplicateIsbn -> book.isbn?.let { repo.findByIsbn(it) }
                else -> null
            } ?: return@launch
            _moreBy.value = _moreBy.value?.let { s ->
                s.copy(books = s.books.map { if (it.book == book) it.copy(inLibrary = saved) else it })
            }
        }
    }

    /** The library's copy: same ISBN, or the same title (before any subtitle) by the same first author. */
    private fun inLibrary(book: AuthorBook, library: List<Book>): Book? {
        val author = searchKey(book.authors.substringBefore(',').trim())
        return library.firstOrNull { book.isbn != null && it.isbn == book.isbn }
            ?: library.firstOrNull { titleKey(it.title) == titleKey(book.title) && searchKey(it.authors.substringBefore(',').trim()) == author }
    }

    private fun titleKey(title: String) = searchKey(title.substringBefore(':').trim())
}
