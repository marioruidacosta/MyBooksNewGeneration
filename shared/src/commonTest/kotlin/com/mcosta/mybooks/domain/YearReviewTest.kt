package com.mcosta.mybooks.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YearReviewTest {
    private val utc = TimeZone.UTC
    private fun day(text: String) = LocalDate.parse(text).atStartOfDayIn(utc).toEpochMilliseconds()
    private fun read(title: String, date: String, rating: Int? = null, pages: Int? = null, id: Long = 0) =
        Book(id = id, title = title, status = ReadingStatus.READ, dateRead = day(date), rating = rating, pageCount = pages)

    private val library = listOf(
        read("Stoner", "2026-04-12", rating = 5, pages = 288, id = 1),
        read("Solaris", "2026-09-06", rating = 4, pages = 204, id = 2),
        read("Memorial do Convento", "2026-10-24", rating = 5, pages = 352, id = 3),
        read("O Castelo", "2026-10-02", rating = 3, id = 4),
        read("Dune", "2025-12-30", rating = 5, pages = 688, id = 5),
        Book(id = 6, title = "Os Maias", status = ReadingStatus.TO_READ, pageCount = 700),
    )

    @Test
    fun theYearsBooksInTheOrderRead() {
        val review = yearReview(library, 2026, utc, goal = 25)
        assertEquals(listOf("Stoner", "Solaris", "O Castelo", "Memorial do Convento"), review.books.map { it.title })
        assertEquals("Stoner", review.first?.title)
        assertEquals("Memorial do Convento", review.last?.title)
        assertEquals(25, review.goal)
        assertEquals(4, review.stats.books)
    }

    @Test
    fun favouritesAreTheBestScoredLatestFirst() {
        assertEquals(listOf("Memorial do Convento", "Stoner", "Solaris"), yearReview(library, 2026, utc).favourites.map { it.title })
    }

    @Test
    fun longestShortestAndBusiestMonth() {
        val review = yearReview(library, 2026, utc)
        assertEquals("Memorial do Convento", review.longest?.title)
        assertEquals("Solaris", review.shortest?.title)
        assertEquals(10, review.busiestMonth)
    }

    @Test
    fun aYearWithOneOrNoBooks() {
        val one = yearReview(library, 2025, utc)
        assertEquals("Dune", one.longest?.title)
        assertNull(one.shortest)                 // the same book isn't both
        assertNull(one.busiestMonth)             // one book in December is no busiest month
        val none = yearReview(library, 2020, utc)
        assertEquals(listOf<Book>(), none.books)
        assertNull(none.busiestMonth)
        assertNull(none.longest)
        assertNull(none.first)
    }
}
