package com.mcosta.mybooks.ui.releases

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.data.releases.ReleaseBook
import com.mcosta.mybooks.data.releases.ReleasesRepository
import com.mcosta.mybooks.data.releases.StoreLink
import com.mcosta.mybooks.data.releases.storeLinks
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.ui.social.LibraryShelf
import com.mcosta.mybooks.ui.detail.AuthorPanel
import com.mcosta.mybooks.ui.detail.AuthorPanelLoader
import com.mcosta.mybooks.data.authors.AuthorInfoSource
import kotlinx.coroutines.flow.first
import com.mcosta.mybooks.data.authors.catalogTitleKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReleaseBookState(
    val book: ReleaseBook? = null,
    val loaded: Boolean = false,
    val coverUrl: String? = null,
    val description: String? = null,
    val inLibrary: Boolean = false,
    val message: String? = null,
    val stores: List<StoreLink> = emptyList(),
)

/** A new book: details, cover and description from the lookup sources, Add to my books and Where to buy. */
class ReleaseBookViewModel(
    private val releases: ReleasesRepository,
    library: BookRepository,
    private val month: String,
    private val bookId: String,
    /** Author biographies for the header's third panel; null shows only the library's books by them. */
    authors: AuthorInfoSource? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(ReleaseBookState())
    val state: StateFlow<ReleaseBookState> = _state.asStateFlow()
    private val shelf = LibraryShelf(library)
    private val authorLoader = AuthorPanelLoader(library, authors)

    /** The header's author panel, loaded when first shown ([loadAuthor]). */
    val author: StateFlow<AuthorPanel?> = authorLoader.panel
    private val lookupRepo = library

    /** The cover found by the lookup sources (saved with the book); null until found. */
    private var lookedUpCover: String? = null

    init {
        viewModelScope.launch {
            val book = releases.book(month, bookId)
            if (book == null) {
                _state.update { it.copy(loaded = true) }
                return@launch
            }
            _state.update {
                it.copy(
                    book = book,
                    loaded = true,
                    coverUrl = book.listCover,
                    stores = storeLinks(book.isbn),
                    inLibrary = shelf.contains(book.isbn, book.title, book.authors.joinToString(", ")),
                )
            }
            val found = (lookupRepo.lookup(book.isbn) as? LookupResult.Found)?.book ?: return@launch
            lookedUpCover = found.coverUrl
            _state.update {
                it.copy(coverUrl = found.coverUrl ?: it.coverUrl, description = found.description?.takeIf(String::isNotBlank))
            }
        }
    }

    fun addToLibrary(status: ReadingStatus) {
        val book = _state.value.book ?: return
        viewModelScope.launch {
            val added = shelf.add(
                Book(
                    isbn = book.isbn, title = book.title, authors = book.authors.joinToString(", "),
                    publisher = book.publisher, publishedYear = book.year, coverUrl = lookedUpCover, status = status,
                )
            )
            _state.update {
                it.copy(
                    message = LibraryShelf.message(added, status) ?: it.message,
                    inLibrary = it.inLibrary || added != LibraryShelf.Added.FAILED,
                )
            }
        }
    }

    /** Loads the author panel once, when the header shows it (the book is loaded first). */
    fun loadAuthor() {
        viewModelScope.launch {
            val book = state.first { it.loaded }.book ?: return@launch
            authorLoader.load(book.authors.joinToString(", ")) { it.isbn == book.isbn || catalogTitleKey(it.title) == catalogTitleKey(book.title) }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }
}

/** The header's details panel for a new book: label and value per line, only what is known. */
fun releaseDetails(book: ReleaseBook): List<Pair<String, String>> = listOfNotNull(
    book.publisher?.takeIf { it.isNotBlank() }?.let { "Publisher" to it },
    listOfNotNull(book.place?.takeIf { it.isNotBlank() }, book.year?.toString()).joinToString(" · ").takeIf { it.isNotEmpty() }
        ?.let { "Published" to it },
    book.translators.takeIf { it.isNotEmpty() }?.let { "Translated by" to it.joinToString(", ") },
    "ISBN" to book.isbn,
)
