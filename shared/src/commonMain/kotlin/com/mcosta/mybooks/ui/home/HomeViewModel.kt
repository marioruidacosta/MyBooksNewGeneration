package com.mcosta.mybooks.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.ChallengeRepository
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ChallengeProgress
import com.mcosta.mybooks.domain.Pace
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.domain.pace
import com.mcosta.mybooks.ui.components.CoverRef
import com.mcosta.mybooks.domain.readIn
import com.mcosta.mybooks.ui.today as currentDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** The most covers a shelf row fans out. */
const val SHELF_COVERS = 5

/** A shelf row on the home screen ([status] null is All books); [covers] are its most recently added books'. */
data class Shelf(val status: ReadingStatus?, val count: Int, val covers: List<CoverRef>)

data class HomeState(
    val loaded: Boolean = false,
    /** Books being read, most recently started first. */
    val reading: List<Book> = emptyList(),
    /** To read, To buy, Read, then All books. */
    val shelves: List<Shelf> = emptyList(),
    val year: Int = 0,
    /** This year's challenge, or null when it has no goal. */
    val challenge: ChallengeProgress? = null,
    val pace: Pace? = null,
)

/** The home screen: what's being read, this year's challenge, and a row per shelf. */
class HomeViewModel(
    repo: BookRepository,
    challenges: ChallengeRepository,
    today: () -> LocalDate = { currentDate() },
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {
    val state: StateFlow<HomeState> = combine(repo.observeBooks(null, ""), challenges.observe()) { books, goals ->
        val date = today()
        val goal = goals.firstOrNull { it.year == date.year }
        val progress = goal?.let { ChallengeProgress(it.year, it.goal, books.readIn(it.year, timeZone).size) }
        HomeState(
            loaded = true,
            reading = books.filter { it.status == ReadingStatus.READING }
                .sortedWith(compareByDescending<Book> { it.dateStarted ?: Long.MIN_VALUE }.thenByDescending { it.dateAdded }),
            shelves = SHELVES.map { status ->
                val shelf = if (status == null) books else books.filter { it.status == status }
                Shelf(
                    status,
                    shelf.size,
                    shelf.sortedByDescending { it.dateAdded }.take(SHELF_COVERS).map { CoverRef(it.coverUrl, it.title, it.authors) },
                )
            },
            year = date.year,
            challenge = progress,
            pace = progress?.pace(date),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    private companion object {
        val SHELVES = listOf(ReadingStatus.TO_READ, ReadingStatus.WISHLIST, ReadingStatus.READ, null)
    }
}
