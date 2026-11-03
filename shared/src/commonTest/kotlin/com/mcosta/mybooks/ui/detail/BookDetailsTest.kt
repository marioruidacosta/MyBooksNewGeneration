package com.mcosta.mybooks.ui.detail

import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class BookDetailsTest {
    private val day = { ms: Long -> "day$ms" }

    @Test
    fun everyKnownDetailInOrder() {
        val book = Book(
            title = "Dune", isbn = "9780441013593", publisher = "Ace", publishedYear = 2005, pageCount = 688,
            status = ReadingStatus.READ, dateAdded = 1, dateStarted = 2, dateRead = 3,
        )
        assertEquals(
            listOf(
                "Publisher" to "Ace", "Published" to "2005", "Pages" to "688", "ISBN" to "9780441013593",
                "Added" to "day1", "Started" to "day2", "Finished" to "day3",
            ),
            bookDetails(book, day),
        )
    }

    @Test
    fun unknownDetailsAreLeftOut() {
        val book = Book(title = "Notes", status = ReadingStatus.TO_READ, dateAdded = 5)
        assertEquals(listOf("Added" to "day5"), bookDetails(book, day))
    }

    @Test
    fun readingBookShowsWhenItWasStarted() {
        val book = Book(title = "Now", status = ReadingStatus.READING, dateAdded = 1, dateStarted = 4)
        assertEquals(listOf("Added" to "day1", "Started" to "day4"), bookDetails(book, day))
    }
}
