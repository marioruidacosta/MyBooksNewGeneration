package com.mcosta.mybooks.domain

import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** How many names the top-author and top-publisher lists show. */
const val TOP_NAMES = 3

/** A year of reading in numbers: books finished that year (marked Read with a read date in it). */
data class YearStats(
    val year: Int,
    val books: Int,
    /** Books finished in each month, January first. */
    val perMonth: List<Int>,
    /** Pages of the books that have a page count; [withoutPages] don't. */
    val pages: Int,
    val withoutPages: Int,
    /** Average score of the [rated] books, or null when none is rated. */
    val averageScore: Double?,
    val rated: Int,
    /** First authors (Goodreads lists translators as further authors) and publishers, most books first, ties A–Z. */
    val topAuthors: List<Pair<String, Int>>,
    val topPublishers: List<Pair<String, Int>>,
    val previousYearBooks: Int,
)

fun readingStats(books: List<Book>, year: Int, timeZone: TimeZone): YearStats {
    val read = books.readIn(year, timeZone)
    val perMonth = IntArray(12)
    for (book in read) {
        val month = Instant.fromEpochMilliseconds(book.dateRead!!).toLocalDateTime(timeZone).month.number
        perMonth[month - 1]++
    }
    val ratings = read.mapNotNull { it.rating }
    return YearStats(
        year = year,
        books = read.size,
        perMonth = perMonth.toList(),
        pages = read.sumOf { it.pageCount ?: 0 },
        withoutPages = read.count { it.pageCount == null },
        averageScore = ratings.takeIf { it.isNotEmpty() }?.average(),
        rated = ratings.size,
        topAuthors = top(read.map { it.authors.substringBefore(',').trim() }),
        topPublishers = top(read.mapNotNull { it.publisher?.trim() }),
        previousYearBooks = books.readIn(year - 1, timeZone).size,
    )
}

/** The years with books read, newest first, always including [current]. */
fun statsYears(books: List<Book>, current: Int, timeZone: TimeZone): List<Int> =
    (books.mapNotNull { it.readYear(timeZone) } + current).distinct().sortedDescending()

private fun top(names: List<String>): List<Pair<String, Int>> =
    names.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().toList()
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { searchKey(it.first) })
        .take(TOP_NAMES)
