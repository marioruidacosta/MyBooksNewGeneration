package com.mcosta.mybooks.ui.releases

import com.mcosta.mybooks.data.releases.ReleaseBook
import com.mcosta.mybooks.ui.library.LibrarySort
import com.mcosta.mybooks.ui.library.SortChoice
import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseSortTest {
    private fun book(id: String, title: String, authors: List<String> = emptyList(), publisher: String? = null) =
        ReleaseBook(id = id, isbn = "9789895682799", title = title, authors = authors, publisher = publisher, genre = "novel")

    private val books = listOf(
        book("1", "O Castelo", listOf("Franz Kafka"), "Relógio d'Água"),
        book("2", "Zorba", listOf("Nikos Kazantzakis"), "Asa"),
        book("3", "Ana", emptyList(), "[s.n.]"),
        book("4", "Ensaio", listOf("José Saramago"), "Porto Editora"),
    )

    private fun List<ReleaseBook>.titles() = map { it.title }

    @Test
    fun titleAuthorAndPublisherBothWays() {
        assertEquals(listOf("Ana", "O Castelo", "Ensaio", "Zorba"), books.sortedFor(SortChoice(LibrarySort.TITLE)).titles())
        assertEquals(listOf("Zorba", "Ensaio", "O Castelo", "Ana"), books.sortedFor(SortChoice(LibrarySort.TITLE, descending = true)).titles())
        // Surname: Kafka, Kazantzakis, Saramago; no author last either way.
        assertEquals(listOf("O Castelo", "Zorba", "Ensaio", "Ana"), books.sortedFor(SortChoice(LibrarySort.AUTHOR)).titles())
        assertEquals(listOf("Ensaio", "Zorba", "O Castelo", "Ana"), books.sortedFor(SortChoice(LibrarySort.AUTHOR, descending = true)).titles())
        // "[s.n.]" is no publisher: last either way.
        assertEquals(listOf("Zorba", "Ensaio", "O Castelo", "Ana"), books.sortedFor(SortChoice(LibrarySort.PUBLISHER)).titles())
        assertEquals(listOf("O Castelo", "Ensaio", "Zorba", "Ana"), books.sortedFor(SortChoice(LibrarySort.PUBLISHER, descending = true)).titles())
    }

    @Test
    fun onlyTitleAuthorAndPublisherAreOffered() {
        assertEquals(listOf(LibrarySort.TITLE, LibrarySort.AUTHOR, LibrarySort.PUBLISHER), RELEASE_SORTS)
    }
}
