package com.mcosta.mybooks.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.authors.AuthorBook
import com.mcosta.mybooks.data.authors.AuthorInfoSource
import com.mcosta.mybooks.data.authors.catalogTitleKey
import com.mcosta.mybooks.data.authors.findInLibrary
import com.mcosta.mybooks.data.releases.StoreLink
import com.mcosta.mybooks.data.releases.storeLinksFor
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CatalogBookState(
    val loaded: Boolean = false,
    /** The library's copy, once added (or if it already was). */
    val inLibrary: Book? = null,
    /** "Where to buy": shown while the book isn't in the library, or is in To buy. */
    val stores: List<StoreLink> = emptyList(),
)

/**
 * A catalogue book (from "More by …") on its own page: add it to To read or To buy, and where to buy it. [book]
 * is null when the page was reopened after the app closed (the book is no longer known).
 */
class CatalogBookViewModel(
    private val repo: BookRepository,
    val book: AuthorBook?,
    /** Author biographies for the header's third panel; null shows only the library's books by them. */
    authors: AuthorInfoSource? = null,
) : ViewModel() {
    private val authorLoader = AuthorPanelLoader(repo, authors)

    /** The header's author panel, loaded when first shown ([loadAuthor]). */
    val author: StateFlow<AuthorPanel?> = authorLoader.panel

    /** Loads the author panel once, when the header shows it. */
    fun loadAuthor() {
        val b = book ?: return
        viewModelScope.launch {
            authorLoader.load(b.authors) { (b.isbn != null && it.isbn == b.isbn) || catalogTitleKey(it.title) == catalogTitleKey(b.title) }
        }
    }

    val state: StateFlow<CatalogBookState> = repo.observeBooks(null, "")
        .map { library ->
            val copy = book?.let { findInLibrary(it, library) }
            val shopping = book != null && (copy == null || copy.status == ReadingStatus.WISHLIST)
            CatalogBookState(loaded = true, inLibrary = copy, stores = if (shopping) storeLinksFor(book!!.isbn, book.title, book.authors) else emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogBookState())

    fun add(status: ReadingStatus) {
        val b = book ?: return
        if (state.value.inLibrary != null) return
        viewModelScope.launch {
            repo.save(
                Book(
                    title = b.title, authors = b.authors, isbn = b.isbn, publisher = b.publisher, publishedYear = b.year,
                    pageCount = b.pageCount, coverUrl = b.coverUrl, description = b.description, status = status,
                ),
            )
        }
    }

}

/** The header's details panel for a catalogue book: label and value per line, only what is known. */
fun catalogDetails(book: AuthorBook): List<Pair<String, String>> = listOfNotNull(
    book.publisher?.takeIf { it.isNotBlank() }?.let { "Publisher" to it },
    book.year?.let { "Published" to it.toString() },
    book.pageCount?.let { "Pages" to it.toString() },
    book.isbn?.let { "ISBN" to it },
)
