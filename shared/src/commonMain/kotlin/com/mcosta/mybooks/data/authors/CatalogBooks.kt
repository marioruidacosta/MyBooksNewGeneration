package com.mcosta.mybooks.data.authors

import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.searchKey

/**
 * Catalogue books (e.g. from "More by …") opened on their own page. Navigation carries only a key, so the book
 * is kept here while the app runs; after the app is closed the key finds nothing and the page says so.
 */
class CatalogBooks {
    private val books = mutableMapOf<String, AuthorBook>()
    private var next = 0

    fun put(book: AuthorBook): String {
        val key = "c${next++}"
        books[key] = book
        return key
    }

    fun get(key: String): AuthorBook? = books[key]
}

/** "Ensaio sobre a Cegueira: romance" and "Ensaio Sobre a Cegueira" are the same book. */
fun catalogTitleKey(title: String): String = searchKey(title.substringBefore(':').trim())

/** The library's copy of a catalogue book: same ISBN, or the same title (before any subtitle) by the same first author. */
fun findInLibrary(book: AuthorBook, library: List<Book>): Book? {
    val author = searchKey(book.authors.substringBefore(',').trim())
    val title = catalogTitleKey(book.title)
    return library.firstOrNull { book.isbn != null && it.isbn == book.isbn }
        ?: library.firstOrNull { catalogTitleKey(it.title) == title && searchKey(it.authors.substringBefore(',').trim()) == author }
}
