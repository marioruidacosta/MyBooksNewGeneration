package com.mcosta.mybooks.ui.library

import com.mcosta.mybooks.domain.Book
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchGroupsTest {
    private val books = (1..8).map { Book(id = it.toLong(), title = "Book $it") }

    /** Search's "In your library" group shows five books until "Show all" is tapped. */
    @Test
    fun libraryGroupShowsFiveUntilExpanded() {
        assertEquals((1L..5L).toList(), libraryPreview(books, expanded = false).map { it.id })
        assertEquals(8, libraryPreview(books, expanded = true).size)
        assertEquals(3, libraryPreview(books.take(3), expanded = false).size)
        assertEquals(5, LIBRARY_PREVIEW)
    }
}
