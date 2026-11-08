package com.mcosta.mybooks.data.releases

import com.mcosta.mybooks.domain.IsbnQuery
import com.mcosta.mybooks.domain.isbnQuery
import com.mcosta.mybooks.domain.searchKey

/** Most results each outside section of Search shows. */
const val OUTSIDE_SEARCH_LIMIT = 20

/** A New in Portugal book found by Search, with the month it is listed in. */
data class ReleaseHit(val month: String, val book: ReleaseBook)

/**
 * New in Portugal's books matching [query]: the book with that ISBN when the query is one, otherwise those whose
 * title or an author contains it (ignoring case and accents). Newest month first; a book listed in several months
 * counts once, in the newest; at most [limit].
 */
fun matchReleases(all: AllMonths, query: String, limit: Int = OUTSIDE_SEARCH_LIMIT): List<ReleaseHit> {
    val isbn = (isbnQuery(query) as? IsbnQuery.Valid)?.isbn13
    val key = searchKey(query.trim())
    if (isbn == null && key.isEmpty()) return emptyList()
    val seen = mutableSetOf<String>()
    val hits = mutableListOf<ReleaseHit>()
    for (month in all.months) {
        for (book in month.books) {
            val matches = if (isbn != null) book.isbn == isbn
            else key in searchKey(book.title) || book.authors.any { key in searchKey(it) }
            if (matches && seen.add(book.id)) {
                hits += ReleaseHit(month.month, book)
                if (hits.size == limit) return hits
            }
        }
    }
    return hits
}
