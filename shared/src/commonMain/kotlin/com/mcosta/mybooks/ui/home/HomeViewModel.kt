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
import com.mcosta.mybooks.domain.hasTag
import com.mcosta.mybooks.data.PinnedTags
import kotlinx.coroutines.flow.flowOf
import com.mcosta.mybooks.ui.components.CoverRef
import com.mcosta.mybooks.ui.detail.ProgressInput
import com.mcosta.mybooks.ui.detail.parseProgress
import com.mcosta.mybooks.domain.readIn
import com.mcosta.mybooks.ui.today as currentDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** The most covers a shelf row fans out. */
const val SHELF_COVERS = 5

/** A shelf row on the home screen ([status] null is All books); [covers] are its most recently added books'. */
data class Shelf(val status: ReadingStatus?, val count: Int, val covers: List<CoverRef>)

/** A row of books on the home screen that is not a shelf: a pinned tag, or the books lent out. */
data class BookGroup(val name: String, val count: Int, val covers: List<CoverRef>)

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
    /** Tags pinned to the home screen, in the order pinned. */
    val tags: List<BookGroup> = emptyList(),
    /** Books lent to someone; null when every book is at home. */
    val lent: BookGroup? = null,
)

/** The home screen: what's being read, this year's challenge, and a row per shelf. */
class HomeViewModel(
    private val repo: BookRepository,
    challenges: ChallengeRepository,
    /** Tags pinned to the home screen; null shows none. */
    pinnedTags: PinnedTags? = null,
    today: () -> LocalDate = { currentDate() },
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {
    val state: StateFlow<HomeState> = combine(repo.observeBooks(null, ""), challenges.observe(), pinnedTags?.tags ?: flowOf(emptyList())) { books, goals, pins ->
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
            tags = pins.map { tag -> group(tag, books.filter { it.hasTag(tag) }) },
            lent = books.filter { it.lentTo != null }.takeIf { it.isNotEmpty() }?.let { group("Lent out", it) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    private val _progressError = MutableStateFlow<String?>(null)
    /** What's wrong with the progress typed in the quick-update sheet. */
    val progressError: StateFlow<String?> = _progressError.asStateFlow()

    private val _finishPrompt = MutableStateFlow<Book?>(null)
    /** A book whose progress just reached the end: "Finished? Mark as Read". */
    val finishPrompt: StateFlow<Book?> = _finishPrompt.asStateFlow()

    /**
     * Quick update from the Reading strip: a page, or a percentage when [byPages] is false. Returns whether it was
     * saved (the sheet closes); reaching the last page or 100% asks whether the book is finished.
     */
    fun updateProgress(id: Long, input: String, byPages: Boolean): Boolean {
        val progress = parseProgress(input, byPages)
        if (progress is ProgressInput.Invalid) {
            _progressError.value = progress.message
            return false
        }
        _progressError.value = null
        viewModelScope.launch {
            when (progress) {
                is ProgressInput.Page -> repo.setProgressPages(id, progress.page)
                is ProgressInput.Percent -> repo.setProgressPercent(id, progress.percent)
                is ProgressInput.Invalid -> Unit
            }
            val book = repo.getBook(id)
            if (book != null && book.status == ReadingStatus.READING && book.progressPercent == 100) _finishPrompt.value = book
        }
        return true
    }

    fun clearProgressError() {
        _progressError.value = null
    }

    /** "Mark as Read": the read date is today, and the celebration follows. */
    fun markFinished() {
        val book = _finishPrompt.value ?: return
        _finishPrompt.value = null
        viewModelScope.launch { repo.setStatus(book.id, ReadingStatus.READ) }
    }

    fun dismissFinish() {
        _finishPrompt.value = null
    }

    private companion object {
        val SHELVES = listOf(ReadingStatus.TO_READ, ReadingStatus.WISHLIST, ReadingStatus.READ, null)

        /** The newest covers first, like the shelves. */
        fun group(name: String, books: List<Book>) = BookGroup(
            name, books.size, books.sortedByDescending { it.dateAdded }.take(SHELF_COVERS).map { CoverRef(it.coverUrl, it.title, it.authors) },
        )
    }
}
