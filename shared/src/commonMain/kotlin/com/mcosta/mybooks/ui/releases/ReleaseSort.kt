package com.mcosta.mybooks.ui.releases

import com.mcosta.mybooks.data.releases.NOT_STATED
import com.mcosta.mybooks.data.releases.ReleaseBook
import com.mcosta.mybooks.data.releases.publisherKey
import com.mcosta.mybooks.domain.searchKey
import com.mcosta.mybooks.ui.library.LibrarySort
import com.mcosta.mybooks.ui.library.SortChoice
import com.mcosta.mybooks.ui.library.authorKey
import com.mcosta.mybooks.ui.library.titleKey

/** The orders New in Portugal lists offer: these books have no score or date added. */
val RELEASE_SORTS = listOf(LibrarySort.TITLE, LibrarySort.AUTHOR, LibrarySort.PUBLISHER)

/**
 * New books in [choice] order, by the shelves' rules (leading articles and accents ignored, authors by surname).
 * Books without an author or publisher stay last in either direction; ties go A to Z by title.
 */
fun List<ReleaseBook>.sortedFor(choice: SortChoice): List<ReleaseBook> {
    // Keys computed once per book, not per comparison.
    val titles = associateWith { titleKey(it.title) }
    val byTitle = compareBy<ReleaseBook> { titles[it] }.thenBy { it.id }
    fun by(key: (ReleaseBook) -> String?): Comparator<ReleaseBook> {
        val keys = associateWith(key)
        val primary = compareBy<ReleaseBook> { keys[it] }.let { if (choice.descending) it.reversed() else it }
        return compareBy<ReleaseBook> { keys[it] == null }.then(primary).then(byTitle)
    }
    val order = when (choice.order) {
        LibrarySort.AUTHOR -> by { authorKey(it.authors.joinToString(", ")) }
        LibrarySort.PUBLISHER -> by { b -> b.publisherKey().takeIf { it != NOT_STATED }?.let(::searchKey) }
        else -> if (choice.descending) compareByDescending<ReleaseBook> { titles[it] }.thenBy { it.id } else byTitle
    }
    return sortedWith(order)
}
