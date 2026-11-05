package com.mcosta.mybooks.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** What the home-screen widget shows: the book being read and this year's challenge. */
data class ReadingSummary(
    /** The book most recently started, or null when nothing is being read. */
    val current: Book?,
    /** How many other books are being read at the same time. */
    val alsoReading: Int,
    /** This year's challenge, or null without a goal. */
    val challenge: ChallengeProgress?,
)

fun readingSummary(books: List<Book>, challenges: List<Challenge>, today: LocalDate, timeZone: TimeZone): ReadingSummary {
    val reading = books.filter { it.status == ReadingStatus.READING }
        .sortedWith(compareByDescending<Book> { it.dateStarted ?: Long.MIN_VALUE }.thenByDescending { it.dateAdded })
    val goal = challenges.firstOrNull { it.year == today.year }
    return ReadingSummary(
        current = reading.firstOrNull(),
        alsoReading = (reading.size - 1).coerceAtLeast(0),
        challenge = goal?.let { ChallengeProgress(it.year, it.goal, books.readIn(it.year, timeZone).size) },
    )
}
