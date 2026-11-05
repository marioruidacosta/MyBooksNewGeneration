package com.mcosta.mybooks.data.releases

import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.data.authors.LibraryIndex
import com.mcosta.mybooks.data.suggestions.tasteProfile
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.searchKey

/** A month of New in Portugal with books for you: by authors you have, or from publishers you read most. */
data class ReleaseAlert(
    val month: String,
    val byAuthors: List<ReleaseBook>,
    val byPublishers: List<ReleaseBook>,
    /** The authors and publishers matched, most books first. */
    val authors: List<String>,
    val publishers: List<String>,
) {
    val count: Int get() = byAuthors.size + byPublishers.size

    val title: String get() = "New in Portugal · ${monthLabel(month, complete = true)}"

    /** "3 by José Saramago, Afonso Cruz and 1 more · 1 from Antígona". */
    val text: String
        get() = listOfNotNull(
            byAuthors.size.takeIf { it > 0 }?.let { "$it by ${names(authors)}" },
            byPublishers.size.takeIf { it > 0 }?.let { "$it from ${names(publishers)}" },
        ).joinToString(" · ")

    private fun names(list: List<String>) =
        if (list.size <= 2) list.joinToString(" and ") else "${list.take(2).joinToString(", ")} and ${list.size - 2} more"
}

/**
 * The [books] of [month] by an author of a book in [library] (first authors, as in suggestions), or else from one of
 * its favourite publishers; books already in the library are left out. Null when there are none.
 */
fun releaseAlert(month: String, books: List<ReleaseBook>, library: List<Book>): ReleaseAlert? {
    val owned = LibraryIndex(library)
    val authors = library.map { it.authors.substringBefore(',').trim() }.filter { it.isNotEmpty() }.associateBy { searchKey(it) }
    val publishers = tasteProfile(library).publishers.associateBy { searchKey(it.name) }
    val byAuthors = mutableListOf<Pair<ReleaseBook, String>>()
    val byPublishers = mutableListOf<Pair<ReleaseBook, String>>()
    for (b in books) {
        if (owned.contains(b.isbn.takeIf { it.isNotBlank() }, b.title, b.authors.joinToString(", "))) continue
        val author = authors[searchKey(b.authors.firstOrNull().orEmpty().trim())]
        val publisher = b.publisherKey().takeIf { it != NOT_STATED }?.let { publishers[searchKey(it)] }
        when {
            author != null -> byAuthors += b to author
            publisher != null -> byPublishers += b to publisher.name
        }
    }
    if (byAuthors.isEmpty() && byPublishers.isEmpty()) return null
    fun ranked(matches: List<Pair<ReleaseBook, String>>) =
        matches.groupingBy { it.second }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { searchKey(it.key) }).map { it.key }
    return ReleaseAlert(month, byAuthors.map { it.first }, byPublishers.map { it.first }, ranked(byAuthors), ranked(byPublishers))
}

/**
 * The monthly notification of New in Portugal: on or off, and which month was last announced. Each finished month is
 * announced once; turning it on starts from the next month (the current list is already in the app).
 */
class ReleaseAlerts(
    private val releases: ReleasesRepository,
    private val library: suspend () -> List<Book>,
    private val prefs: Preferences?,
) {
    val enabled: Boolean get() = prefs?.get(ENABLED) == "true"

    suspend fun setEnabled(on: Boolean) {
        prefs?.set(ENABLED, on.toString())
        if (on && prefs?.get(LAST) == null) latestFinished()?.let { prefs?.set(LAST, it) }
    }

    /** The alert for a finished month not announced yet, if it has books for you; the month counts as announced. */
    suspend fun check(): ReleaseAlert? {
        if (!enabled) return null
        val latest = latestFinished() ?: return null
        val last = prefs?.get(LAST)
        if (last != null && latest <= last) return null
        val books = releases.books(latest)
        if (books.offline) return null
        prefs?.set(LAST, latest)
        if (last == null) return null      // first check: start from the next month
        return releaseAlert(latest, books.value, library())
    }

    private suspend fun latestFinished(): String? {
        val months = releases.months()
        if (months.offline) return null
        return months.value.filter { it.complete }.maxOfOrNull { it.month }
    }

    private companion object {
        const val ENABLED = "alerts.releases"
        const val LAST = "alerts.releases.month"
    }
}
