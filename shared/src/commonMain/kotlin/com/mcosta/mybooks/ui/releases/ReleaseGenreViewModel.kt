package com.mcosta.mybooks.ui.releases

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.data.releases.MonthBooks
import com.mcosta.mybooks.data.releases.ReleaseBook
import com.mcosta.mybooks.data.releases.ReleaseFilter
import com.mcosta.mybooks.data.releases.matches
import com.mcosta.mybooks.data.releases.ReleaseGenre
import com.mcosta.mybooks.data.releases.ReleasesRepository
import com.mcosta.mybooks.data.releases.ReleasesUnavailable
import com.mcosta.mybooks.ui.library.LibrarySort
import com.mcosta.mybooks.ui.library.SortChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Instant

data class GenreBooksState(
    /** The books by month, newest first; one group for a single month, only months with books for all months. */
    val groups: List<MonthBooks> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** Set when the saved copy is shown because the download failed. */
    val savedAt: Instant? = null,
    /** Whether the month is over, from the saved index (null until known; single month only). */
    val monthComplete: Boolean? = null,
    /** Months that couldn't be loaded (all months only). */
    val missing: List<String> = emptyList(),
    /** How the books are sorted (within each month): one choice for every New in Portugal list. */
    val sort: SortChoice = SortChoice(),
) {
    val books: List<ReleaseBook> get() = groups.flatMap { it.books }
}

/** One month's (or, with a null [month], every month's) new books of one genre, author or publisher. */
class ReleaseGenreViewModel(
    private val repo: ReleasesRepository,
    private val month: String?,
    private val filter: ReleaseFilter,
    /** Remembers the sort between launches; null keeps it for this session only. */
    private val prefs: Preferences? = null,
) : ViewModel() {
    constructor(repo: ReleasesRepository, month: String, genre: ReleaseGenre) : this(repo, month, ReleaseFilter.Genre(genre))

    private val _state = MutableStateFlow(GenreBooksState(sort = saved()))
    val state: StateFlow<GenreBooksState> = _state.asStateFlow()

    /** The books as loaded (A to Z), before sorting. */
    private var loaded: List<MonthBooks> = emptyList()

    init {
        retry()
    }

    fun retry() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                if (month == null) loadAll() else loadMonth(month)
            } catch (e: ReleasesUnavailable) {
                _state.update { it.copy(error = LOAD_ERROR) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    /** Sorts by [order], A to Z (only Title, Author and Publisher are offered). */
    fun setSort(order: LibrarySort) {
        if (order in RELEASE_SORTS) sort(SortChoice(order))
    }

    fun setDescending(descending: Boolean) = sort(_state.value.sort.copy(descending = descending))

    private fun sort(choice: SortChoice) {
        prefs?.set(SORT_KEY, choice.encode())
        _state.update { it.copy(sort = choice, groups = sorted(loaded, choice)) }
    }

    private suspend fun loadMonth(month: String) {
        // "(so far)" follows the month list, like the month picker, not the phone's clock.
        val complete = repo.savedMonth(month)?.complete
        _state.update { it.copy(monthComplete = complete) }
        val cached = repo.books(month)
        loaded = listOf(MonthBooks(month, complete ?: false, cached.value.filter { b -> b.matches(filter) }))
        _state.update { it.copy(groups = sorted(loaded, it.sort), savedAt = cached.savedAt.takeIf { cached.offline }) }
    }

    private suspend fun loadAll() {
        val all = repo.allMonths()
        loaded = all.months.map { m -> m.copy(books = m.books.filter { b -> b.matches(filter) }) }.filter { it.books.isNotEmpty() }
        _state.update { it.copy(groups = sorted(loaded, it.sort), savedAt = all.savedAt, missing = all.missing) }
    }

    /** Sorted within each month; the months stay newest first. */
    private fun sorted(groups: List<MonthBooks>, choice: SortChoice) = groups.map { it.copy(books = it.books.sortedFor(choice)) }

    private fun saved(): SortChoice = SortChoice.decode(prefs?.get(SORT_KEY))?.takeIf { it.order in RELEASE_SORTS } ?: SortChoice()

    private companion object {
        const val SORT_KEY = "releases.sort"
    }
}
