package com.mcosta.mybooks.data.suggestions

import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class TasteProfileTest {
    private var id = 1L
    private fun read(title: String, author: String, rating: Int? = null, publisher: String? = null, dateRead: Long = id) =
        Book(id = id++, title = title, authors = author, rating = rating, publisher = publisher, status = ReadingStatus.READ, dateRead = dateRead)
    private fun owned(title: String, author: String, publisher: String?, status: ReadingStatus = ReadingStatus.TO_READ) =
        Book(id = id++, title = title, authors = author, publisher = publisher, status = status)

    @Test
    fun favouritesAreTheBestRatedReadBooks() {
        val books = listOf(
            read("Caim", "José Saramago", 4, dateRead = 10),
            read("Memorial", "José Saramago", 5, dateRead = 5),
            read("Meh", "Someone", 2),
            read("Unrated", "Other"),
            owned("Not read", "José Saramago", null),
        )
        assertEquals(listOf("Memorial", "Caim"), tasteProfile(books).favourites.map { it.title })
    }

    @Test
    fun withoutRatingsTheReadBooksAreTheFavourites() {
        val books = listOf(read("A", "X", dateRead = 1), read("B", "Y", dateRead = 3), owned("C", "Z", null))
        assertEquals(listOf("B", "A"), tasteProfile(books).favourites.map { it.title })
    }

    @Test
    fun authorsWeightedByScoreFirstAuthorOnly() {
        val books = listOf(
            read("Memorial", "José Saramago", 5),        // 3
            read("Caim", "Jose Saramago", 4),           // 2  → Saramago 5
            read("Contos", "Albert Camus, Tradutor", 5), // 3
            read("A Peste", "Albert Camus"),            // 1  → Camus 4
            read("Bad", "Bad Author", 1),               // 0
            read("Only read", "Ana Lima"),              // 1
        )
        val authors = tasteProfile(books).authors
        assertEquals(listOf("José Saramago", "Albert Camus", "Ana Lima"), authors.map { it.name })
        assertEquals("Memorial", authors[0].because.title)            // their best-rated book
    }

    @Test
    fun publishersWithAtLeastThreeBooks() {
        val books = listOf(
            owned("1", "a", "Antígona"), owned("2", "b", "antigona"), read("3", "c", publisher = "Antígona"),
            owned("4", "d", "Asa"), owned("5", "e", "Asa"),
            owned("6", "f", "[s.n.]"), owned("7", "f", "[s.n.]"), owned("8", "f", "[s.n.]"),
        )
        assertEquals(listOf(FavouritePublisher("Antígona", 3)), tasteProfile(books).publishers)
    }

    @Test
    fun anEmptyLibraryHasNoTaste() {
        val p = tasteProfile(emptyList())
        assertEquals(emptyList(), p.favourites)
        assertEquals(emptyList(), p.authors)
        assertEquals(emptyList(), p.publishers)
    }
}
