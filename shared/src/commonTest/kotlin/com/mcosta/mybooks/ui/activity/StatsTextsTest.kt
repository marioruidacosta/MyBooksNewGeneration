package com.mcosta.mybooks.ui.activity

import com.mcosta.mybooks.domain.YearStats
import kotlin.test.Test
import kotlin.test.assertEquals

class StatsTextsTest {
    private fun stats(books: Int, previous: Int) =
        YearStats(2026, books, List(12) { 0 }, 0, 0, null, 0, emptyList(), emptyList(), previous)

    @Test
    fun comparedWithLastYear() {
        assertEquals("vs 2025: +5", comparedText(stats(19, 14)))
        assertEquals("vs 2025: −2", comparedText(stats(12, 14)))
        assertEquals("Same as 2025", comparedText(stats(14, 14)))
    }

    @Test
    fun thousandsAreSpaced() {
        assertEquals("0", groupedNumber(0))
        assertEquals("999", groupedNumber(999))
        assertEquals("12 345", groupedNumber(12345))
        assertEquals("1 234 567", groupedNumber(1234567))
    }
}
