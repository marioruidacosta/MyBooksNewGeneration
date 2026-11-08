package com.mcosta.mybooks.share

import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublicStateTest {
    /** Review focus: a description with newlines and tabs, or a long one, never trips the server's 600 limit. */
    @Test
    fun blurbIsCollapsedAndCut() {
        assertEquals("Uma sátira deliciosa", blurbOf("Uma\n\tsátira  deliciosa "))
        assertEquals(BLURB_MAX, blurbOf("a".repeat(700))!!.length)
        assertNull(blurbOf("  \n "))
        assertNull(blurbOf(null))
    }

    @Test
    fun publicStateCarriesPagesAndBlurb() {
        val book = Book(title = "A Relíquia", status = ReadingStatus.READING, pageCount = 344, currentPage = 182, description = "Teodorico.")
        val state = book.publicState("s")
        assertEquals(344, state.pages)
        assertEquals(182, state.currentPage)
        assertEquals("Teodorico.", state.blurb)
        assertTrue(publicFieldsDiffer(book, book.copy(currentPage = 183)))
        assertTrue(publicFieldsDiffer(book, book.copy(description = "Outra.")))
    }
}
