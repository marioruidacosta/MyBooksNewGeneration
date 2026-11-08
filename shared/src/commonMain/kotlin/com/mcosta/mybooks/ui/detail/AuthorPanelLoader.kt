package com.mcosta.mybooks.ui.detail

import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.authors.AuthorInfoSource
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.domain.searchKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * The book header's author panel, shared by the library, New in Portugal and catalogue book pages: the first author,
 * the library's other books by them (read first, then A–Z), then their biography from [authors].
 */
internal class AuthorPanelLoader(private val repo: BookRepository, private val authors: AuthorInfoSource?) {
    private val _panel = MutableStateFlow<AuthorPanel?>(null)
    val panel: StateFlow<AuthorPanel?> = _panel.asStateFlow()
    private var started = false

    /** Loads the panel once for [authorsText] ("A, B": the first is used); [isThisBook] leaves the page's own book out. */
    suspend fun load(authorsText: String, isThisBook: (Book) -> Boolean) {
        if (started) return
        started = true
        val name = authorsText.substringBefore(',').trim()
        if (name.isEmpty()) {
            _panel.value = AuthorPanel(name = "", loading = false)
            return
        }
        val key = searchKey(name)
        val theirs = repo.observeBooks(null, "").first()
            .filter { !isThisBook(it) && searchKey(it.authors.substringBefore(',').trim()) == key }
            .sortedWith(compareBy({ it.status != ReadingStatus.READ }, { searchKey(it.title) }))
        _panel.value = AuthorPanel(name, otherBooks = theirs, loading = authors != null)
        val info = authors?.author(name)
        _panel.value = _panel.value?.copy(info = info, loading = false)
    }
}
