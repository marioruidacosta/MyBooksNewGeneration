package com.mcosta.mybooks.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.ChallengeRepository
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ChallengeProgress
import com.mcosta.mybooks.domain.MAX_GOAL
import com.mcosta.mybooks.domain.Pace
import com.mcosta.mybooks.domain.pace
import com.mcosta.mybooks.domain.readIn
import com.mcosta.mybooks.domain.readWithoutDate
import com.mcosta.mybooks.domain.YearStats
import com.mcosta.mybooks.domain.readingStats
import com.mcosta.mybooks.domain.statsYears
import com.mcosta.mybooks.ui.today as currentDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** A year's challenge and where it stands. */
data class YearChallenge(val progress: ChallengeProgress, val pace: Pace)

data class ReadingActivityState(
    val loaded: Boolean = false,
    val year: Int = 0,
    /** This year's challenge, or null when it has no goal. */
    val current: YearChallenge? = null,
    /** Books finished this year, most recent first. */
    val readThisYear: List<Book> = emptyList(),
    /** The other years with a goal, newest first. */
    val others: List<YearChallenge> = emptyList(),
    /** Read books without a read date (they count toward no year). */
    val withoutDate: Int = 0,
    /** The years the statistics can show (with books read, and this year), newest first. */
    val statsYears: List<Int> = emptyList(),
    /** Statistics of the chosen year. */
    val stats: YearStats? = null,
)

/** "Your reading activity": yearly challenges and the books read toward them. */
class ReadingActivityViewModel(
    repo: BookRepository,
    private val challenges: ChallengeRepository,
    today: () -> LocalDate = { currentDate() },
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {
    /** The statistics' year; null is this year. */
    private val statsYear = MutableStateFlow<Int?>(null)

    val state: StateFlow<ReadingActivityState> = combine(repo.observeBooks(null, ""), challenges.observe(), statsYear) { books, goals, chosen ->
        val date = today()
        fun challenge(year: Int, goal: Int): YearChallenge {
            val progress = ChallengeProgress(year, goal, books.readIn(year, timeZone).size)
            return YearChallenge(progress, progress.pace(date))
        }
        ReadingActivityState(
            loaded = true,
            year = date.year,
            current = goals.firstOrNull { it.year == date.year }?.let { challenge(it.year, it.goal) },
            readThisYear = books.readIn(date.year, timeZone),
            others = goals.filter { it.year != date.year }.map { challenge(it.year, it.goal) },
            withoutDate = books.readWithoutDate().size,
            statsYears = statsYears(books, date.year, timeZone),
            stats = readingStats(books, chosen ?: date.year, timeZone),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingActivityState())

    fun showStats(year: Int) {
        statsYear.value = year
    }

    /**
     * Sets [year]'s goal from what was typed; returns the problem to show, or null when saved. Years from 1900
     * to next year can have a goal.
     */
    fun setGoal(year: String, goal: String): String? {
        val y = year.trim().toIntOrNull()
        val g = goal.trim().toIntOrNull()
        val next = state.value.year.takeIf { it > 0 }?.plus(1)
        return when {
            y == null || y < 1900 || (next != null && y > next) -> "Enter a year up to ${next ?: "next year"}"
            g == null || g !in 1..MAX_GOAL -> "Enter a number of books from 1 to $MAX_GOAL"
            else -> {
                viewModelScope.launch { challenges.setGoal(y, g) }
                null
            }
        }
    }

    fun removeGoal(year: Int) {
        viewModelScope.launch { challenges.remove(year) }
    }
}

/** The books read in one year (or, for a null [year], read without a date), most recent first. */
class ReadYearViewModel(repo: BookRepository, val year: Int?, timeZone: TimeZone = TimeZone.currentSystemDefault()) : ViewModel() {
    val books: StateFlow<List<Book>?> = repo.observeBooks(null, "").map { books ->
        if (year == null) books.readWithoutDate().sortedByDescending { it.dateAdded } else books.readIn(year, timeZone)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
