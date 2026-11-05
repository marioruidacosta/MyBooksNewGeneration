package com.mcosta.mybooks.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.PinnedTags
import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.BookFilter
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.domain.hasTag
import com.mcosta.mybooks.domain.seriesOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryState(
    val tab: ReadingStatus? = null,
    val query: String = "",
    val books: List<Book> = emptyList(),
    val sort: SortChoice = SortChoice(),
    /** Covers in a grid rather than the list (one choice for every shelf, remembered). */
    val grid: Boolean = false,
    /** The tag, series or lent books shown, across every status; null for a shelf. */
    val filter: BookFilter? = null,
    /** The tags on this shelf's books, offered as chips to narrow it down (none for a tag, series or loans). */
    val tags: List<String> = emptyList(),
    /** The chip chosen from [tags], if any. */
    val tag: String? = null,
    /** A tag's books are shown as a row on the home screen. */
    val pinned: Boolean = false,
) {
    /** The orders offered on this tab: Score only for Read books. */
    val sorts: List<LibrarySort> get() = if (tab == ReadingStatus.READ) LibrarySort.entries else LibrarySort.entries - LibrarySort.SCORE

    /** A series is always in its own order. */
    val sortable: Boolean get() = filter !is BookFilter.Series
}

/** Where swiping a book to the right moves it: Read, or To read for a book already Read. */
fun swipeTarget(book: Book): ReadingStatus = if (book.status == ReadingStatus.READ) ReadingStatus.TO_READ else ReadingStatus.READ

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repo: BookRepository,
    /** Remembers the sort orders between launches; null keeps them for this session only. */
    private val prefs: Preferences? = null,
    /** The shelf shown ([ReadingStatus] null is All books). */
    section: ReadingStatus? = null,
    /** Finishes deletions still waiting for Undo when the screen closes; null in tests that don't need it. */
    private val appScope: CoroutineScope? = null,
    /** A tag's, a series' or the lent books instead of a shelf ([section] is then null). */
    private val filter: BookFilter? = null,
    /** Which tags are rows on the home screen; null in tests that don't involve them. */
    private val pinnedTags: PinnedTags? = null,
) : ViewModel() {
    private val tab = MutableStateFlow(section)
    private val query = MutableStateFlow("")
    /** Each tab's own sort (All is the null tab), as saved. */
    private val sorts = MutableStateFlow(TABS.associateWith { SortChoice.decode(prefs?.get(key(it))) ?: SortChoice() })
    /** Books swiped away, hidden while their Undo is offered, then deleted. */
    private val hidden = MutableStateFlow<Set<Long>>(emptySet())
    private val grid = MutableStateFlow(prefs?.get(GRID_KEY) == "true")
    private val chosenTag = MutableStateFlow<String?>(null)
    private val pinned: StateFlow<List<String>> = pinnedTags?.tags ?: MutableStateFlow(emptyList())

    val state: StateFlow<LibraryState> = combine(tab, query, sorts, hidden, grid) { t, q, s, h, g -> Shown(t, q, s.getValue(t), h, g) }
        .flatMapLatest { v ->
            combine(repo.observeBooks(v.tab, v.query), chosenTag, pinned) { all, chosen, pins ->
                val books = all.filter { it.id !in v.hidden && (filter == null || filter.matches(it)) }
                val tags = if (filter != null) emptyList() else tagsOf(books)
                val tag = chosen?.let { c -> tags.firstOrNull { it.equals(c, ignoreCase = true) } }
                val shown = if (tag == null) books else books.filter { it.hasTag(tag) }
                LibraryState(
                    tab = v.tab,
                    query = v.query,
                    books = if (filter is BookFilter.Series) shown.sortedWith(SERIES_ORDER) else shown.sortedFor(v.sort),
                    sort = v.sort,
                    grid = v.grid,
                    filter = filter,
                    tags = tags,
                    tag = tag,
                    pinned = filter is BookFilter.Tag && pins.any { it.equals(filter.name, ignoreCase = true) },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState(filter = filter))

    private data class Shown(val tab: ReadingStatus?, val query: String, val sort: SortChoice, val hidden: Set<Long>, val grid: Boolean)

    fun selectTab(status: ReadingStatus?) {
        tab.value = status
    }

    fun setQuery(q: String) {
        query.value = q
    }

    /** Narrows the shelf to one of its tags; null shows all its books again. */
    fun chooseTag(tag: String?) {
        chosenTag.value = tag
    }

    /** Shows this tag's books as a row on the home screen, or stops showing them. */
    fun setPinned(on: Boolean) {
        val tag = (filter as? BookFilter.Tag)?.name ?: return
        pinnedTags?.setPinned(tag, on)
    }

    /** Sorts this tab by [order], in its usual direction: A to Z, or newest or highest score first. */
    fun setSort(order: LibrarySort) {
        if (order == LibrarySort.SCORE && tab.value != ReadingStatus.READ) return
        save(SortChoice(order, descending = order.newestOrHighestFirst))
    }

    fun setDescending(descending: Boolean) = save(sorts.value.getValue(tab.value).copy(descending = descending))

    fun setGrid(on: Boolean) {
        grid.value = on
        prefs?.set(GRID_KEY, on.toString())
    }

    /** Swiped right: Read (or To read if it already was); [undoStatus] with the same book puts it back. */
    fun swipeStatus(book: Book) {
        viewModelScope.launch { repo.setStatus(book.id, swipeTarget(book)) }
    }

    /** Puts a swiped book back exactly as it was, dates and progress included. */
    fun undoStatus(before: Book) {
        viewModelScope.launch { repo.save(before) }
    }

    /** Swiped left: hidden now, deleted by [delete] unless [undoDelete] comes first. */
    fun hide(id: Long) {
        hidden.value += id
    }

    fun undoDelete(id: Long) {
        hidden.value -= id
    }

    fun delete(id: Long) {
        (appScope ?: viewModelScope).launch {
            repo.delete(id)
            hidden.value -= id
        }
    }

    override fun onCleared() {
        // Leaving the screen while Undo was still offered: the deletion goes ahead.
        val pending = hidden.value
        if (pending.isNotEmpty()) appScope?.launch { pending.forEach { repo.delete(it) } }
    }

    suspend fun findByIsbn(isbn: String): Book? = repo.findByIsbn(isbn)

    private fun save(choice: SortChoice) {
        val t = tab.value
        sorts.value += t to choice
        prefs?.set(key(t), choice.encode())
    }

    private companion object {
        val TABS = listOf(null) + ReadingStatus.entries
        const val GRID_KEY = "library.grid"
        fun key(tab: ReadingStatus?) = "library.sort.${tab?.name ?: "ALL"}"

        val SERIES_ORDER = compareBy<Book> { seriesOrder(it.seriesNumber) }.thenBy { it.title.lowercase() }

        /** Most used first, then A to Z; the same tag in other capitals once. */
        fun tagsOf(books: List<Book>): List<String> = books.flatMap { it.tags }.groupBy { it.lowercase() }.values
            .sortedWith(compareByDescending<List<String>> { it.size }.thenBy { it.first().lowercase() })
            .map { it.first() }
    }
}
