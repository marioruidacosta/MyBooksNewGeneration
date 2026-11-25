package com.mcosta.mybooks.ui.detail

import com.mcosta.mybooks.data.authors.catalogTitleKey
import com.mcosta.mybooks.data.books.BookFacts
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.ui.formatDate

/** The details behind the cover on the book page, label to value, leaving out what isn't known. */
fun bookDetails(book: Book, date: (Long) -> String = ::formatDate): List<Pair<String, String>> = listOfNotNull(
    book.publisher?.takeIf { it.isNotBlank() }?.let { "Publisher" to it },
    book.publishedYear?.let { "Published" to it.toString() },
    book.pageCount?.let { "Pages" to it.toString() },
    book.isbn?.let { "ISBN" to it },
    book.dateAdded.takeIf { it > 0 }?.let { "Added" to date(it) },
    book.dateStarted?.let { "Started" to date(it) },
    book.dateRead?.let { "Finished" to date(it) },
)

/** The lines Wikidata adds under them; the original title only when the book isn't already it. */
fun factRows(facts: BookFacts, bookTitle: String): List<Pair<String, String>> = listOfNotNull(
    facts.firstPublished?.let { "First published" to it },
    facts.originalTitle?.takeIf { catalogTitleKey(it) != catalogTitleKey(bookTitle) }?.let { t ->
        "Original title" to (facts.originalLanguage?.let { "$t ($it)" } ?: t)
    },
    facts.genres.takeIf { it.isNotEmpty() }?.let { "Genre" to it.take(3).joinToString(", ") },
    facts.awards.takeIf { it.isNotEmpty() }?.let { "Awards" to it.joinToString(", ") },
    facts.follows?.let { "Follows" to it },
    facts.followedBy?.let { "Followed by" to it },
)
