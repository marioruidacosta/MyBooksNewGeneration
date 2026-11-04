package com.mcosta.mybooks.data.authors

import com.mcosta.mybooks.domain.Book
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryIndexTest {
    private val library = listOf(
        Book(title = "Caim: romance", authors = "José Saramago, Tradutor", isbn = "9789722120005"),
        Book(title = "Os Maias", authors = "Eça de Queirós"),
    )
    private val index = LibraryIndex(library)

    @Test
    fun matchesLikeFindInLibrary() {
        val candidates = listOf(
            AuthorBook("Outro título", "Someone", null, null, "9789722120005", null, null, null),   // same ISBN
            AuthorBook("CAIM", "Jose Saramago", null, null, null, null, null, null),               // title and author
            AuthorBook("Os Maias: episódios", "Eça de Queirós", null, null, "9789720000000", null, null, null),
            AuthorBook("Os Maias", "Someone Else", null, null, null, null, null, null),           // other author
            AuthorBook("Novo", "José Saramago", null, null, null, null, null, null),
        )
        assertEquals(candidates.map { findInLibrary(it, library) != null }, candidates.map { index.contains(it) })
        assertEquals(listOf(true, true, true, false, false), candidates.map { index.contains(it) })
    }
}
