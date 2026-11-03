package com.mcosta.mybooks.ui.celebrate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.ChallengeRepository
import com.mcosta.mybooks.data.ReadingEvents
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ChallengeProgress
import com.mcosta.mybooks.domain.Pace
import com.mcosta.mybooks.domain.pace
import com.mcosta.mybooks.domain.readIn
import com.mcosta.mybooks.ui.activity.countText
import com.mcosta.mybooks.ui.activity.text
import com.mcosta.mybooks.ui.today as currentDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** A book just finished, and what it means for the year. */
data class Celebration(
    val book: Book,
    val year: Int,
    /** Books finished this year, this one included. */
    val readThisYear: Int,
    /** This year's challenge, or null without a goal. */
    val challenge: ChallengeProgress?,
    val pace: Pace?,
)

/** "19 of 30 books · 1 book ahead of schedule", or without a goal "That's 19 books in 2026". */
fun Celebration.line(): String {
    val c = challenge
    return if (c != null) "${c.countText()} · ${pace!!.text()}" else "That's ${if (readThisYear == 1) "1 book" else "$readThisYear books"} in $year"
}

/** Shows a celebration whenever a book is marked as Read, from any screen. */
class CelebrationViewModel(
    private val repo: BookRepository,
    private val challenges: ChallengeRepository,
    events: ReadingEvents,
    private val today: () -> LocalDate = { currentDate() },
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {
    private val _celebration = MutableStateFlow<Celebration?>(null)
    val celebration: StateFlow<Celebration?> = _celebration.asStateFlow()

    init {
        viewModelScope.launch { events.finished.collect { show(it) } }
    }

    private suspend fun show(id: Long) {
        val book = repo.getBook(id) ?: return
        val date = today()
        val read = repo.observeBooks(null, "").first().readIn(date.year, timeZone).size
        val goal = challenges.all().firstOrNull { it.year == date.year }
        val progress = goal?.let { ChallengeProgress(it.year, it.goal, read) }
        _celebration.value = Celebration(book, date.year, read, progress, progress?.pace(date))
    }

    /** Scores the finished book from the celebration. */
    fun rate(rating: Int) {
        val c = _celebration.value ?: return
        _celebration.value = c.copy(book = c.book.copy(rating = rating))
        viewModelScope.launch { repo.setRating(c.book.id, rating) }
    }

    fun dismiss() {
        _celebration.value = null
    }
}
