package com.mcosta.mybooks.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.data.authors.AuthorBook
import com.mcosta.mybooks.data.authors.LibraryIndex
import com.mcosta.mybooks.domain.Book
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import com.mcosta.mybooks.data.releases.AllMonths
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mcosta.mybooks.data.releases.ReleaseHit
import com.mcosta.mybooks.data.releases.ReleaseIndex
import com.mcosta.mybooks.domain.IsbnQuery
import com.mcosta.mybooks.domain.isbnQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Search waits this long after the last keystroke before looking outside the library. */
const val OUTSIDE_SEARCH_DELAY_MS = 800L

/** Shorter queries (unless an ISBN) aren't searched outside the library. */
const val OUTSIDE_SEARCH_MIN = 3

/** One outside section of Search. */
sealed interface Section<out T> {
    data object Idle : Section<Nothing>
    data object Searching : Section<Nothing>
    data class Results<out T>(val items: List<T>) : Section<T>
    data object Empty : Section<Nothing>
    data object Failed : Section<Nothing>
}

enum class OutsideSection { RELEASES, ONLINE }

data class OutsideSearchState(
    /** The sections are on screen (the search started). */
    val shown: Boolean = false,
    /** The query looks like an ISBN but its check digit is wrong. */
    val invalidIsbn: Boolean = false,
    /** The query's ISBN-13, when it is a valid ISBN (for "Add it yourself"). */
    val isbn: String? = null,
    val releases: Section<ReleaseHit> = Section.Idle,
    val online: Section<AuthorBook> = Section.Idle,
    /** New in Portugal's months downloaded so far, of [monthsTotal] (0 when not downloading). */
    val monthsLoaded: Int = 0,
    val monthsTotal: Int = 0,
    /** The library, to mark outside results already in it ([owns]); null until loaded. */
    val library: LibraryIndex? = null,
) {
    /** Whether this New in Portugal book is already in the library. */
    fun owns(hit: ReleaseHit): Boolean = library?.contains(hit.book.isbn, hit.book.title, hit.book.authors.joinToString(", ")) == true

    /** Whether this public-sources book is already in the library. */
    fun owns(book: AuthorBook): Boolean = library?.contains(book) == true
}

/**
 * Search outside the library: after a pause in typing (whether or not the library has matches), New in Portugal ([releases],
 * loaded once) and public catalogues ([online]) are searched at once, each section on its own. A newer query cancels
 * an older one, whose results are never shown; the same query again (coming back to Search) changes nothing.
 * [releases] reports (months done, months listed) as it downloads; matching thousands of books runs on [compute].
 */
class OutsideSearchViewModel(
    private val releases: suspend (onProgress: (done: Int, total: Int) -> Unit) -> AllMonths,
    private val online: suspend (String) -> List<AuthorBook>,
    /** The whole library, to mark results already in it. */
    library: Flow<List<Book>> = emptyFlow(),
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(OutsideSearchState())
    val state: StateFlow<OutsideSearchState> = _state.asStateFlow()

    /** The query being searched outside, or null. */
    private var current: String? = null
    private var waiting: Job? = null
    private val running = mutableMapOf<OutsideSection, Job>()
    private var index: ReleaseIndex? = null
    private var owned: LibraryIndex? = null

    init {
        viewModelScope.launch {
            library.collect { books ->
                val built = withContext(compute) { LibraryIndex(books) }
                owned = built
                _state.update { it.copy(library = built) }
            }
        }
    }

    fun onQuery(query: String) {
        val q = query.trim()
        val asIsbn = isbnQuery(q)
        val isbn = (asIsbn as? IsbnQuery.Valid)?.isbn13
        val searchable = isbn != null || q.length >= OUTSIDE_SEARCH_MIN
        if (searchable && q == current) return
        waiting?.cancel()
        running.values.forEach { it.cancel() }
        running.clear()
        current = q.takeIf { searchable }
        _state.value = OutsideSearchState(invalidIsbn = asIsbn == IsbnQuery.Invalid, isbn = isbn, library = owned)
        if (!searchable) return
        waiting = viewModelScope.launch {
            delay(OUTSIDE_SEARCH_DELAY_MS)
            _state.update { it.copy(shown = true) }
            search(OutsideSection.RELEASES, q)
            search(OutsideSection.ONLINE, q)
        }
    }

    /** Searches a section that failed again. */
    fun retry(section: OutsideSection) {
        val q = current ?: return
        if (_state.value.shown) search(section, q)
    }

    private fun search(section: OutsideSection, q: String) {
        running[section]?.cancel()
        set(section, Section.Searching)
        running[section] = viewModelScope.launch {
            val result = try {
                when (section) {
                    OutsideSection.RELEASES -> loadIndex().let { withContext(compute) { it.match(q) } }.asSection()
                    OutsideSection.ONLINE -> online(q).asSection()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Section.Failed
            }
            if (current == q) set(section, result)
        }
    }

    /**
     * New in Portugal's months: kept only when all of them came from the network, so a partial or saved (offline)
     * load is loaded again for the next search. None at all is a failure (Retry), not "nothing found".
     */
    private suspend fun loadIndex(): ReleaseIndex {
        index?.let { return it }
        val all = try {
            releases { done, total -> _state.update { it.copy(monthsLoaded = done, monthsTotal = total) } }
        } finally {
            _state.update { it.copy(monthsLoaded = 0, monthsTotal = 0) }
        }
        if (all.months.isEmpty() && all.missing.isNotEmpty()) throw IllegalStateException("No New in Portugal month could be loaded")
        val built = withContext(compute) { ReleaseIndex(all) }
        if (all.missing.isEmpty() && all.savedAt == null) index = built
        return built
    }

    @Suppress("UNCHECKED_CAST")
    private fun set(section: OutsideSection, value: Section<*>) = _state.update {
        when (section) {
            OutsideSection.RELEASES -> it.copy(releases = value as Section<ReleaseHit>)
            OutsideSection.ONLINE -> it.copy(online = value as Section<AuthorBook>)
        }
    }

    private fun <T> List<T>.asSection(): Section<T> = if (isEmpty()) Section.Empty else Section.Results(this)
}

/** Search's "In your library" group shows this many books until "Show all" is tapped. */
const val LIBRARY_PREVIEW = 5

/** The library matches Search shows: the first [LIBRARY_PREVIEW], or all once [expanded]. */
fun libraryPreview(books: List<Book>, expanded: Boolean): List<Book> = if (expanded) books else books.take(LIBRARY_PREVIEW)
