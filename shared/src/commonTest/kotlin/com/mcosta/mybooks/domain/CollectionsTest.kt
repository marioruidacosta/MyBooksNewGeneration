package com.mcosta.mybooks.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectionsTest {
    @Test
    fun goodreadsSeriesComesOutOfTheTitle() {
        assertEquals(SeriesTitle("The Fellowship of the Ring", "The Lord of the Rings", "1"), splitSeries("The Fellowship of the Ring (The Lord of the Rings, #1)"))
        assertEquals(SeriesTitle("The Hobbit", "Middle-earth Universe", "0.5"), splitSeries("The Hobbit (Middle-earth Universe #0.5)"))
        assertEquals(SeriesTitle("Box", "Dune", "1-3"), splitSeries("Box (Dune, #1–3)"))
        assertEquals(SeriesTitle("O Ano da Morte (de Ricardo Reis)", "Obras, Vol. 2", "4"), splitSeries("O Ano da Morte (de Ricardo Reis) (Obras, Vol. 2, #4)"))
    }

    @Test
    fun parenthesesWithoutANumberAreLeftAlone() {
        assertNull(splitSeries("Ensaio sobre a Cegueira (Edição Especial)"))
        assertNull(splitSeries("Catch-22"))
        assertNull(splitSeries("(#1)"))
    }

    @Test
    fun goodreadsTitleIsWrittenBackTheSameWay() {
        assertEquals("The Hobbit (Middle-earth, #0.5)", withSeries("The Hobbit", "Middle-earth", "0.5"))
        assertEquals("Dune (Dune)", withSeries("Dune", "Dune", null))
        assertEquals("Dune", withSeries("Dune", null, "1"))
        val title = withSeries("Box", "Dune", "1-3")
        assertEquals(SeriesTitle("Box", "Dune", "1-3"), splitSeries(title))
    }

    @Test
    fun seriesOrderIsByNumberWithBooksWithoutOneLast() {
        val numbers = listOf("10", null, "2", "1-3", "0.5")
        assertEquals(listOf("0.5", "1-3", "2", "10", null), numbers.sortedBy { seriesOrder(it) })
    }

    @Test
    fun tagsAreTidiedAndKeptOnceWhateverTheirCapitals() {
        assertEquals(listOf("Classics", "gift from Ana"), cleanTags(listOf(" Classics ", "", "gift   from Ana", "classics", "  ")))
        assertEquals(MAX_TAG_LENGTH, cleanTags(listOf("x".repeat(100))).single().length)
    }

    @Test
    fun filtersMatchTagsSeriesAndLoans() {
        val book = Book(title = "Dune", tags = listOf("Sci-fi"), series = "Dune", seriesNumber = "1", lentTo = "Ana", lentOn = 5)
        assertTrue(BookFilter.Tag("sci-fi").matches(book))
        assertFalse(BookFilter.Tag("classics").matches(book))
        assertTrue(BookFilter.Series("dune").matches(book))
        assertTrue(BookFilter.Lent.matches(book))
        assertFalse(BookFilter.Lent.matches(book.copy(lentTo = null)))
    }
}
