package com.mcosta.mybooks.domain

import kotlinx.datetime.TimeZone

/** How many favourite books the review shows. */
const val REVIEW_FAVOURITES = 3

/** A year of reading told as a story: the numbers, the favourites, the longest and shortest, the busiest month. */
data class YearReview(
    val stats: YearStats,
    /** The year's challenge goal, if one was set. */
    val goal: Int?,
    /** The books read that year, in the order finished. */
    val books: List<Book>,
    /** The best-scored books (at least 4 stars), highest first, then the latest read. */
    val favourites: List<Book>,
    /** Among books with a page count; null without any, and [shortest] null when it would be [longest]. */
    val longest: Book?,
    val shortest: Book?,
    /** The month (1–12) with most books finished, at least 2; ties go to the earlier month. Null otherwise. */
    val busiestMonth: Int?,
) {
    val year: Int get() = stats.year
    val first: Book? get() = books.firstOrNull()
    val last: Book? get() = books.lastOrNull()
}

fun yearReview(library: List<Book>, year: Int, timeZone: TimeZone, goal: Int? = null): YearReview {
    val stats = readingStats(library, year, timeZone)
    val read = library.readIn(year, timeZone).sortedWith(compareBy<Book> { it.dateRead }.thenBy { it.id })
    val paged = read.filter { (it.pageCount ?: 0) > 0 }
    val longest = paged.maxWithOrNull(compareBy<Book> { it.pageCount }.thenBy { it.dateRead })
    val shortest = paged.minWithOrNull(compareBy<Book> { it.pageCount }.thenBy { it.dateRead }).takeIf { it != longest }
    return YearReview(
        stats = stats,
        goal = goal,
        books = read,
        favourites = read.filter { (it.rating ?: 0) >= 4 }
            .sortedWith(compareByDescending<Book> { it.rating }.thenByDescending { it.dateRead })
            .take(REVIEW_FAVOURITES),
        longest = longest,
        shortest = shortest,
        // A month with one book is no "busiest month" (with one book a month, every month would be).
        busiestMonth = stats.perMonth.withIndex().filter { it.value >= 2 }.maxByOrNull { it.value }?.index?.plus(1),
    )
}
