package com.mcosta.mybooks.reader

import androidx.lifecycle.ViewModel
import com.mcosta.mybooks.domain.EbookFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.mcosta.mybooks.domain.Bookmark
import com.mcosta.mybooks.domain.Quote
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.stateIn

/** The ebook file to open and where reading stopped (a Readium locator as JSON). */
data class ReaderFile(val format: EbookFormat, val path: String, val position: String?)

sealed interface ReaderLoad {
    data object Loading : ReaderLoad
    data class Ready(val file: ReaderFile) : ReaderLoad
    data class Failed(val message: String) : ReaderLoad
}

/** Saves the place at most this often while reading, and always when leaving. */
const val SAVE_EVERY_MS = 3_000L

/**
 * The reader's state, apart from Readium: loading the file, saving the place as it moves (at most every
 * [SAVE_EVERY_MS], and when leaving), and "Finished? Mark as Read" when [save] says the end was reached.
 * Saves run in [scope] (the app's), so the last one finishes even after the reader closes.
 */
class ReaderViewModel(
    load: suspend () -> ReaderFile?,
    private val save: suspend (position: String, progression: Double) -> Boolean,
    private val markRead: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** The book's bookmarks, and adding and deleting one. */
    bookmarks: Flow<List<Bookmark>> = emptyFlow(),
    private val addBookmark: suspend (position: String, label: String) -> Unit = { _, _ -> },
    private val deleteBookmark: suspend (id: Long) -> Unit = {},
    /** The book's quotes (those with a position are highlights), and adding or deleting one. */
    quotes: Flow<List<Quote>> = emptyFlow(),
    private val addHighlight: suspend (text: String, position: String) -> Unit = { _, _ -> },
    private val deleteQuote: suspend (id: Long) -> Unit = {},
) : ViewModel() {
    private val _load = MutableStateFlow<ReaderLoad>(ReaderLoad.Loading)
    val load: StateFlow<ReaderLoad> = _load.asStateFlow()

    val bookmarks: StateFlow<List<Bookmark>> = bookmarks.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Quotes highlighted in this ebook, to draw on its pages; typed or photographed quotes have no place. */
    val highlights: StateFlow<List<Quote>> =
        quotes.map { list -> list.filter { it.position != null } }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _shownHighlight = MutableStateFlow<Quote?>(null)
    /** A highlight tapped in the book, shown with Delete. */
    val shownHighlight: StateFlow<Quote?> = _shownHighlight.asStateFlow()

    /** Saves selected [text] at [position] as a quote; blank text is ignored. */
    fun highlight(text: String, position: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        scope.launch { addHighlight(clean, position) }
    }

    fun showHighlight(id: Long) {
        _shownHighlight.value = highlights.value.firstOrNull { it.id == id }
    }

    fun closeHighlight() {
        _shownHighlight.value = null
    }

    fun deleteShownHighlight() {
        val quote = _shownHighlight.value ?: return
        _shownHighlight.value = null
        scope.launch { deleteQuote(quote.id) }
    }

    /** Places to go to (locator JSON), for the reader to follow: a bookmark opened. */
    private val _goTo = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val goTo: SharedFlow<String> = _goTo.asSharedFlow()

    /** Where the reader is now, and what a bookmark there is called. */
    private var here: Pair<String, String>? = null

    /** Bookmarks the current place, labelled with its chapter or page. */
    fun bookmarkHere() {
        val (position, label) = here ?: return
        scope.launch { addBookmark(position, label) }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        scope.launch { deleteBookmark(bookmark.id) }
    }

    fun open(bookmark: Bookmark) {
        _goTo.tryEmit(bookmark.position)
    }

    private val _finishPrompt = MutableStateFlow(false)
    val finishPrompt: StateFlow<Boolean> = _finishPrompt.asStateFlow()

    private var pending: Pair<String, Double>? = null
    private var lastSaved: Long? = null

    init {
        scope.launch {
            _load.value = load()?.let { ReaderLoad.Ready(it) } ?: ReaderLoad.Failed("Couldn't open this book")
        }
    }

    /** The reader moved to [position] ([progression] 0–1 through the book); [bookmarkLabel] names the place. */
    fun position(position: String, progression: Double, bookmarkLabel: String = "") {
        here = position to bookmarkLabel
        pending = position to progression
        val last = lastSaved
        if (last == null || clock() - last >= SAVE_EVERY_MS) flush()
    }

    /** Leaving the reader (or the app going to the background): the place is saved now. */
    fun leave() = flush()

    private fun flush() {
        val (position, progression) = pending ?: return
        pending = null
        lastSaved = clock()
        scope.launch { if (save(position, progression)) _finishPrompt.value = true }
    }

    fun markFinished() {
        _finishPrompt.value = false
        scope.launch { markRead() }
    }

    fun dismissFinish() {
        _finishPrompt.value = false
    }

    fun failed(message: String) {
        _load.value = ReaderLoad.Failed(message)
    }
}
