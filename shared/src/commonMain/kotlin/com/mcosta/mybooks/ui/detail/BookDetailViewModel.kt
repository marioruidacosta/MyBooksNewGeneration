package com.mcosta.mybooks.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.MAX_REVIEW_LENGTH
import com.mcosta.mybooks.data.authors.AuthorInfo
import com.mcosta.mybooks.data.authors.AuthorInfoSource
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.domain.BookFilter
import com.mcosta.mybooks.domain.Quote
import com.mcosta.mybooks.data.QuoteRepository
import com.mcosta.mybooks.data.ebooks.EbookRepository
import com.mcosta.mybooks.data.ebooks.EbookState
import com.mcosta.mybooks.domain.searchKey
import com.mcosta.mybooks.domain.seriesOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The header's author panel: [name] (blank without an author), their biography if found, the library's other books by them. */
data class AuthorPanel(
    val name: String,
    val info: AuthorInfo? = null,
    val otherBooks: List<Book> = emptyList(),
    val loading: Boolean = true,
)

/**
 * Book detail: status, rating and notes edits are saved immediately; the notes and review drafts are also
 * saved when the screen is left ([onCleared]) using [saveScope], which outlives the ViewModel.
 */
class BookDetailViewModel(
    private val repo: BookRepository,
    private val id: Long,
    private val saveScope: CoroutineScope,
    /** Author biographies for the header's third panel; null shows only the library's books by them. */
    private val authors: AuthorInfoSource? = null,
    /** The book's quotes; null shows none (tests that don't involve them). */
    private val quoteRepo: QuoteRepository? = null,
    /** The book's ebook file; null shows no Ebook card (tests that don't involve it). */
    private val ebookRepo: EbookRepository? = null,
) : ViewModel() {
    /** The book's ebook and whether its file is on this phone; null without one. */
    val ebook: StateFlow<EbookState?> = (ebookRepo?.observe(id) ?: flowOf(null))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Deletes the ebook file; bookmarks and highlights stay. */
    fun removeEbook() {
        val repo = ebookRepo ?: return
        saveScope.launch { repo.remove(id) }
    }

    private val authorLoader = AuthorPanelLoader(repo, authors)
    /** The header's author panel, loaded when first shown ([loadAuthor]). */
    val author: StateFlow<AuthorPanel?> = authorLoader.panel

    /** Loads the author panel once: the library's other books by the first author, then their biography. */
    fun loadAuthor() {
        viewModelScope.launch {
            val book = repo.getBook(id) ?: return@launch
            authorLoader.load(book.authors) { it.id == id }
        }
    }

    private val _notesDraft = MutableStateFlow("")
    val notesDraft: StateFlow<String> = _notesDraft.asStateFlow()

    /** Stored notes the draft was last in sync with; null until the book has loaded. */
    private var syncedNotes: String? = null
    private val hasUnsavedNotes get() = syncedNotes != null && _notesDraft.value != syncedNotes

    private val _reviewDraft = MutableStateFlow("")
    /** The public review being typed (at most [MAX_REVIEW_LENGTH] characters). */
    val reviewDraft: StateFlow<String> = _reviewDraft.asStateFlow()
    private var syncedReview: String? = null
    private val hasUnsavedReview get() = syncedReview != null && _reviewDraft.value != syncedReview

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    val book: StateFlow<Book?> = repo.observeBook(id)
        .onEach { b ->
            // Follow notes changed elsewhere (e.g. the Edit screen) unless the user has unsaved typing.
            if (b != null && b.notes != syncedNotes) {
                if (!hasUnsavedNotes) _notesDraft.value = b.notes
                syncedNotes = b.notes
            }
            if (b != null && b.review != syncedReview) {
                if (!hasUnsavedReview) _reviewDraft.value = b.review
                syncedReview = b.review
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** "Don't share this book". */
    val shareExcluded: StateFlow<Boolean> = repo.observeShareExcluded(id).stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setShareExcluded(excluded: Boolean) {
        viewModelScope.launch { repo.setShareExcluded(id, excluded) }
    }

    fun setStatus(status: ReadingStatus) {
        viewModelScope.launch { repo.setStatus(id, status) }
    }

    fun rate(rating: Int?) {
        viewModelScope.launch { repo.setRating(id, rating) }
    }

    /** Every tag in the library, most used first: offered when adding one. */
    val allTags: StateFlow<List<String>> = repo.observeTags().map { tags -> tags.map { it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The books of this book's series in the library, in series order (this one included); empty without a series. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val seriesBooks: StateFlow<List<Book>> = book.map { it?.series }.distinctUntilChanged()
        .flatMapLatest { series ->
            if (series == null) flowOf(emptyList())
            else repo.observeBooks(null, "").map { all ->
                all.filter { BookFilter.Series(series).matches(it) }
                    .sortedWith(compareBy<Book> { seriesOrder(it.seriesNumber) }.thenBy { it.title.lowercase() })
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addTag(tag: String) {
        val b = book.value ?: return
        viewModelScope.launch { repo.setTags(id, b.tags + tag) }
    }

    fun removeTag(tag: String) {
        val b = book.value ?: return
        viewModelScope.launch { repo.setTags(id, b.tags.filterNot { it.equals(tag, ignoreCase = true) }) }
    }

    fun lend(person: String, date: Long) {
        viewModelScope.launch { repo.lend(id, person, date) }
    }

    fun returned() {
        viewModelScope.launch { repo.returned(id) }
    }

    /** The book's quotes in page order. */
    val quotes: StateFlow<List<Quote>> = (quoteRepo?.observe(id) ?: flowOf(emptyList()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Saves a new quote; [page] as typed (blank or not a number means none). */
    fun addQuote(text: String, page: String) {
        val quotes = quoteRepo ?: return
        saveScope.launch { quotes.add(id, text, page.trim().toIntOrNull()) }
    }

    fun updateQuote(quote: Quote, text: String, page: String) {
        val quotes = quoteRepo ?: return
        saveScope.launch { quotes.update(quote.copy(text = text, page = page.trim().toIntOrNull())) }
    }

    /** Deletes a quote; [restoreQuote] with the same quote puts it back (Undo). */
    fun deleteQuote(quote: Quote) {
        val quotes = quoteRepo ?: return
        viewModelScope.launch { quotes.delete(quote.id) }
    }

    fun restoreQuote(quote: Quote) {
        val quotes = quoteRepo ?: return
        viewModelScope.launch { quotes.restore(quote) }
    }

    private val _progressError = MutableStateFlow<String?>(null)
    val progressError: StateFlow<String?> = _progressError.asStateFlow()

    /** Updates reading progress from typed [input]: a page number, or a percentage when [byPages] is false. */
    fun updateProgress(input: String, byPages: Boolean) {
        val progress = parseProgress(input, byPages)
        _progressError.value = (progress as? ProgressInput.Invalid)?.message
        viewModelScope.launch {
            when (progress) {
                is ProgressInput.Page -> repo.setProgressPages(id, progress.page)
                is ProgressInput.Percent -> repo.setProgressPercent(id, progress.percent)
                is ProgressInput.Invalid -> Unit
            }
        }
    }

    fun setCover(coverRef: String) {
        viewModelScope.launch { repo.setCover(id, coverRef) }
    }

    fun setDateStarted(date: Long) {
        viewModelScope.launch { repo.setDateStarted(id, date) }
    }

    fun setDateRead(date: Long) {
        viewModelScope.launch { repo.setDateRead(id, date) }
    }

    fun editNotes(text: String) {
        _notesDraft.value = text
    }

    fun saveNotes() {
        if (_deleted.value || !hasUnsavedNotes) return
        val draft = _notesDraft.value
        syncedNotes = draft
        saveScope.launch { repo.setNotes(id, draft) }
    }

    fun editReview(text: String) {
        _reviewDraft.value = text.take(MAX_REVIEW_LENGTH)
    }

    fun saveReview() {
        if (_deleted.value || !hasUnsavedReview) return
        val draft = _reviewDraft.value
        syncedReview = draft
        saveScope.launch { repo.setReview(id, draft) }
    }

    fun delete() {
        _deleted.value = true
        viewModelScope.launch { repo.delete(id) }
    }

    override fun onCleared() {
        saveNotes()
        saveReview()
    }
}
