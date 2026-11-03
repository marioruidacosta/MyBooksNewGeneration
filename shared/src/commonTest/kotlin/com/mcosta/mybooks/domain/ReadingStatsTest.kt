package com.mcosta.mybooks.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReadingStatsTest {
    private val utc = TimeZone.UTC
    private fun day(text: String) = LocalDate.parse(text).atStartOfDayIn(utc).toEpochMilliseconds()
    private fun read(
        date: String?,
        authors: String = "",
        publisher: String? = null,
        pages: Int? = null,
        rating: Int? = null,
    ) = Book(title = "t", status = ReadingStatus.READ, dateRead = date?.let(::day), authors = authors, publisher = publisher, pageCount = pages, rating = rating)

    private val books = listOf(
        read("2026-01-15", "José Saramago", "Porto Editora", 300, 5),
        read("2026-01-20", "Eça de Queirós, Translator X", "Livros do Brasil", 500, 4),
        read("2026-10-24", "José Saramago", "Porto Editora", null, 3),
        read("2026-10-30", "Ana Lima", "Asa", 200, null),
        read("2025-06-01", "Old Author", "Asa", 100, 2),
        read("2025-07-01", "Old Author", "Asa", 100, 2),
        read(null, "Undated", "Asa", 999, 5),
        Book(title = "Reading", status = ReadingStatus.READING, dateRead = day("2026-03-03"), pageCount = 50),
    )

    @Test
    fun aYearInNumbers() {
        val s = readingStats(books, 2026, utc)
        assertEquals(4, s.books)
        assertEquals(listOf(2, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0), s.perMonth)
        assertEquals(1000, s.pages)
        assertEquals(1, s.withoutPages)
        assertEquals(4.0, s.averageScore)
        assertEquals(3, s.rated)
        assertEquals(2, s.previousYearBooks)
    }

    @Test
    fun topAuthorsUseTheFirstAuthorAndTopPublishersCountBooks() {
        val s = readingStats(books, 2026, utc)
        assertEquals(listOf("José Saramago" to 2, "Ana Lima" to 1, "Eça de Queirós" to 1), s.topAuthors)
        assertEquals(listOf("Porto Editora" to 2, "Asa" to 1, "Livros do Brasil" to 1), s.topPublishers)
    }

    @Test
    fun anEmptyYear() {
        val s = readingStats(books, 2024, utc)
        assertEquals(0, s.books)
        assertEquals(List(12) { 0 }, s.perMonth)
        assertEquals(0, s.pages)
        assertNull(s.averageScore)
        assertEquals(emptyList(), s.topAuthors)
        assertEquals(0, s.previousYearBooks)
    }

    @Test
    fun yearsWithBooksNewestFirstAlwaysIncludingThisYear() {
        assertEquals(listOf(2027, 2026, 2025), statsYears(books, current = 2027, timeZone = utc))
        assertEquals(listOf(2026, 2025), statsYears(books, current = 2026, timeZone = utc))
        assertEquals(listOf(2026), statsYears(emptyList(), current = 2026, timeZone = utc))
    }
}
