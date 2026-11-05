package com.mcosta.mybooks.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReadingSummaryTest {
    private val utc = TimeZone.UTC
    private val today = LocalDate.parse("2026-11-05")
    private fun day(text: String) = LocalDate.parse(text).atStartOfDayIn(utc).toEpochMilliseconds()

    @Test
    fun theLatestStartedBookAndThisYearsChallenge() {
        val books = listOf(
            Book(title = "Older", status = ReadingStatus.READING, dateStarted = day("2026-09-01"), progressPercent = 80),
            Book(title = "Newer", status = ReadingStatus.READING, dateStarted = day("2026-10-30"), progressPercent = 26),
            Book(title = "Done", status = ReadingStatus.READ, dateRead = day("2026-03-01")),
            Book(title = "Last year", status = ReadingStatus.READ, dateRead = day("2025-03-01")),
        )
        val summary = readingSummary(books, listOf(Challenge(2026, 25), Challenge(2025, 10)), today, utc)
        assertEquals("Newer", summary.current?.title)
        assertEquals(1, summary.alsoReading)
        assertEquals(ChallengeProgress(2026, 25, 1), summary.challenge)
    }

    @Test
    fun nothingBeingReadAndNoGoal() {
        val summary = readingSummary(listOf(Book(title = "Later", status = ReadingStatus.TO_READ)), emptyList(), today, utc)
        assertNull(summary.current)
        assertEquals(0, summary.alsoReading)
        assertNull(summary.challenge)
    }
}
