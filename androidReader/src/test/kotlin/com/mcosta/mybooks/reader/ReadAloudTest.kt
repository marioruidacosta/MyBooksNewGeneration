package com.mcosta.mybooks.reader

import com.mcosta.mybooks.domain.EbookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudTest {
    @Test
    fun speedSteps() {
        val steps = generateSequence(1.0) { nextSpeed(it) }.take(6).toList()
        assertEquals(listOf(1.0, 1.25, 1.5, 2.0, 0.75, 1.0), steps)
        assertEquals(0.75, nextSpeed(3.0), 0.0)                // an odd speed starts again from the slowest
        assertEquals("1.25×", speedLabel(1.25))
        assertEquals("1×", speedLabel(1.0))
    }

    @Test
    fun readAloudHiddenForPdf() {
        assertTrue(canReadAloud(EbookFormat.EPUB))
        assertFalse(canReadAloud(EbookFormat.PDF))
    }
}
