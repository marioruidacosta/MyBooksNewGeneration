package com.mcosta.mybooks.data.ebooks

import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.authors.catalogTitleKey
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.EbookMetadata
import com.mcosta.mybooks.domain.Isbn
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.domain.personName
import com.mcosta.mybooks.domain.searchKey
import com.mcosta.mybooks.domain.titleFromFileName
import kotlinx.coroutines.flow.first

/** Which library book an ebook file belongs to, or the new book it makes. */
sealed interface Match {
    val book: Book
    data class Existing(override val book: Book) : Match
    data class New(override val book: Book) : Match
}

/**
 * Matches an ebook file to the library: by ISBN, then by title (before any subtitle) and first author like the
 * catalogue does, among all books; otherwise a new To read book from the file's metadata (or its file name).
 */
class EbookMatcher(private val books: BookRepository) {
    suspend fun match(meta: EbookMetadata, fileName: String): Match {
        val isbn = meta.isbn?.let { Isbn.normalize(it) }
        if (isbn != null) books.findByIsbn(isbn)?.let { return Match.Existing(it) }
        val authors = meta.authors.map(::personName).filter { it.isNotEmpty() }
        val title = meta.title?.trim()?.takeIf { it.isNotEmpty() } ?: titleFromFileName(fileName).ifEmpty { "Untitled" }
        val key = catalogTitleKey(title) to searchKey(authors.firstOrNull().orEmpty())
        if (key.second.isNotEmpty()) {
            books.observeBooks(null, "").first()
                .firstOrNull { catalogTitleKey(it.title) to searchKey(it.authors.substringBefore(',').trim()) == key }
                ?.let { return Match.Existing(it) }
        }
        return Match.New(Book(title = title, authors = authors.joinToString(", "), isbn = isbn, status = ReadingStatus.TO_READ))
    }
}
