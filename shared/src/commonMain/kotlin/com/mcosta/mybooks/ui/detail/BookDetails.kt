package com.mcosta.mybooks.ui.detail

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
