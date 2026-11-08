package com.mcosta.mybooks.data.releases

import com.mcosta.mybooks.domain.IsbnQuery
import com.mcosta.mybooks.domain.isbnQuery
import com.mcosta.mybooks.domain.searchKey

/** Most results each outside section of Search shows. */
const val OUTSIDE_SEARCH_LIMIT = 20

/** A New in Portugal book found by Search, with the month it is listed in ([complete] false for the month in progress). */
data class ReleaseHit(val month: String, val book: ReleaseBook, val complete: Boolean = true)

/**
 * New in Portugal's books ready to search: each book once (in the newest month listing it), with its title's and
 * authors' search keys worked out here, once, rather than for every query (thousands of books).
 */
class ReleaseIndex(all: AllMonths) {
    private class Entry(val hit: ReleaseHit, val title: String, val authors: List<String>)

    private val entries: List<Entry> = buildList {
        val seen = mutableSetOf<String>()
        for (month in all.months) for (book in month.books) {
            if (seen.add(book.id)) add(Entry(ReleaseHit(month.month, book, month.complete), searchKey(book.title), book.authors.map(::searchKey)))
        }
    }

    /**
     * The books matching [query]: the book with that ISBN when the query is one, otherwise those whose title or an
     * author contains it (ignoring case and accents). Newest month first; at most [limit].
     */
    fun match(query: String, limit: Int = OUTSIDE_SEARCH_LIMIT): List<ReleaseHit> {
        val isbn = (isbnQuery(query) as? IsbnQuery.Valid)?.isbn13
        val key = searchKey(query.trim())
        if (isbn == null && key.isEmpty()) return emptyList()
        return entries.asSequence()
            .filter { e -> if (isbn != null) e.hit.book.isbn == isbn else key in e.title || e.authors.any { key in it } }
            .take(limit)
            .map { it.hit }
            .toList()
    }
}

/** [ReleaseIndex.match] for a single query. */
fun matchReleases(all: AllMonths, query: String, limit: Int = OUTSIDE_SEARCH_LIMIT): List<ReleaseHit> = ReleaseIndex(all).match(query, limit)
