package com.mcosta.mybooks.data.goodreads

import com.mcosta.mybooks.data.MAX_REVIEW_LENGTH
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.testing.fixture
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoodreadsCsvTest {
    private val utc = TimeZone.UTC
    private fun day(text: String) = LocalDate.parse(text).atStartOfDayIn(utc).toEpochMilliseconds()
    private val books = GoodreadsCsv.read(fixture("goodreads_export.csv"), utc)

    @Test
    fun readsEveryRow() {
        assertEquals(listOf("Dune", "Good Omens", "Emma", "Old Book", ""), books.map { it.title })
    }

    /** "Dune (Dune, #1)": the series goes to its own field. */
    @Test
    fun seriesComesOutOfTheTitle() {
        assertEquals("Dune", books[0].series)
        assertEquals("1", books[0].seriesNumber)
        assertNull(books[1].series)
    }

    /** Shelves become tags, except the reading ones and To buy (a status here). */
    @Test
    fun shelvesBecomeTags() {
        assertEquals(listOf("classics"), books[2].tags)
        assertEquals(listOf("abandoned"), books[3].tags)
        assertEquals(listOf<String>(), books[0].tags)
    }

    @Test
    fun readsAReadBookWithEverything() {
        val dune = books[0]
        assertEquals("9780441013593", dune.isbn)
        assertEquals("Frank Herbert", dune.authors)
        assertEquals(5, dune.rating)
        assertEquals("Ace", dune.publisher)
        assertEquals(688, dune.pageCount)
        assertEquals(2005, dune.publishedYear)
        assertEquals(ReadingStatus.READ, dune.status)
        assertEquals(day("2024-03-15"), dune.dateRead)
        assertEquals(day("2023-12-01"), dune.dateAdded)
        assertEquals("Loved it.\n\nSpice & \"sand\".", dune.review)
        assertEquals("my note", dune.notes)
    }

    @Test
    fun unratedBookWithTwoAuthorsAndNoIsbn() {
        val omens = books[1]
        assertEquals("Terry Pratchett, Neil Gaiman", omens.authors)
        assertNull(omens.rating)
        assertNull(omens.isbn)
        assertEquals(ReadingStatus.READING, omens.status)
        assertNull(omens.dateRead)
    }

    @Test
    fun shelvesMapToStatuses() {
        assertEquals(ReadingStatus.WISHLIST, books[2].status)     // to-read + "to-buy" shelf
        assertEquals(ReadingStatus.TO_READ, books[3].status)      // a custom exclusive shelf
    }

    /** The 2026 export: no Average Rating column, ratings as decimals (half and quarter stars too). */
    @Test
    fun decimalRatingsRoundToWholeStars() {
        val header = listOf(
            "Book Id", "Title", "Author", "Author l-f", "Additional Authors", "ISBN", "ISBN13", "My Rating", "Publisher",
            "Binding", "Number of Pages", "Year Published", "Original Publication Year", "Date Read", "Date Added",
            "Bookshelves", "Bookshelves with positions", "Exclusive Shelf", "My Review", "Spoiler", "Private Notes",
            "Read Count", "Owned Copies",
        )
        fun row(title: String, rating: String) = listOf(
            "1", title, "A", "", "", "=\"\"", "=\"\"", rating, "Relógio D'Água Editores", "Paperback", "122", "2008", "1860",
            "", "2026/10/27", "", "", "read", "", "", "", "1", "0",
        )
        val csv = Csv.write(listOf(header, row("a", "5.0"), row("b", "4.25"), row("c", "4.5"), row("d", "3.0"), row("e", "0")))
        val read = GoodreadsCsv.read(csv, utc)
        assertEquals(listOf(5, 4, 5, 3, null), read.map { it.rating })
        assertEquals("Relógio D'Água Editores", read[0].publisher)
        assertNull(read[0].dateRead)
    }

    @Test
    fun multiLineReviewKeepsItsLines() {
        assertEquals("Line one\nline two", books[3].review)
    }

    @Test
    fun longReviewIsShortenedToTheLimit() {
        val csv = Csv.write(
            listOf(listOf("Title", "Author", "Exclusive Shelf", "My Review"), listOf("Long", "A", "read", "x".repeat(3000))),
        )
        assertTrue(GoodreadsCsv.read(csv, utc).single().review.length <= MAX_REVIEW_LENGTH)
    }

    @Test
    fun notAGoodreadsFileFails() {
        assertFailsWith<GoodreadsFormatException> { GoodreadsCsv.read("name,age\nAna,3\n", utc) }
        assertFailsWith<GoodreadsFormatException> { GoodreadsCsv.read("", utc) }
    }

    @Test
    fun exportReadsBackTheSame() {
        val mine = listOf(
            Book(
                title = "Dune, \"the\" novel", authors = "Frank Herbert, Brian Herbert", isbn = "9780441013593", rating = 4,
                publisher = "Ace", pageCount = 688, publishedYear = 2005, status = ReadingStatus.READ,
                dateRead = day("2024-03-15"), dateAdded = day("2023-12-01"), review = "Great.\nReally <great> & long.",
                notes = "private", series = "Dune", seriesNumber = "1",
            ),
            Book(title = "Emma", authors = "Jane Austen", status = ReadingStatus.WISHLIST, dateAdded = day("2025-02-02"), tags = listOf("classics", "gift")),
            Book(title = "Now", authors = "B", status = ReadingStatus.READING, dateAdded = day("2025-03-03")),
            Book(title = "Later", authors = "C", status = ReadingStatus.TO_READ, dateAdded = day("2025-04-04")),
        )
        val back = GoodreadsCsv.read(GoodreadsCsv.write(mine, utc), utc)
        assertEquals(mine.map { it.copy(notes = "") }, back)        // private notes are not exported
    }

    /** Goodreads shelf names are lowercase words joined by dashes. */
    @Test
    fun tagsAreWrittenAsShelfNames() {
        val csv = GoodreadsCsv.write(listOf(Book(title = "A", tags = listOf("Gift from Ana", "sci-fi"), status = ReadingStatus.WISHLIST)), utc)
        val row = Csv.parse(csv)
        assertEquals("gift-from-ana, sci-fi, to-buy", row[1][row[0].indexOf("Bookshelves")])
    }

    @Test
    fun csvQuotesCommasQuotesAndNewlines() {
        val rows = listOf(listOf("a,b", "say \"hi\"", "two\nlines", ""), listOf("plain", "", "x", "y"))
        assertEquals(rows, Csv.parse(Csv.write(rows)))
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), Csv.parse("a,b\r\nc,d\r\n"))
    }
}
