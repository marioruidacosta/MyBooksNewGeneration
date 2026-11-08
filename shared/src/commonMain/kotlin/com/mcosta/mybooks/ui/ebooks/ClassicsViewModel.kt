package com.mcosta.mybooks.ui.ebooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.SaveResult
import com.mcosta.mybooks.data.ebooks.Classic
import com.mcosta.mybooks.data.ebooks.ClassicsSource
import com.mcosta.mybooks.data.ebooks.ClassicsUnavailable
import com.mcosta.mybooks.data.ebooks.EbookMatcher
import com.mcosta.mybooks.data.ebooks.EbookRepository
import com.mcosta.mybooks.data.ebooks.Match
import com.mcosta.mybooks.domain.EbookFormat
import com.mcosta.mybooks.domain.EbookMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The search part of Free classics. */
sealed interface ClassicsState {
    data object Idle : ClassicsState
    data object Searching : ClassicsState
    data class Results(val books: List<Classic>) : ClassicsState
    data object Failed : ClassicsState
}

data class ClassicsScreenState(
    val query: String = "",
    val search: ClassicsState = ClassicsState.Idle,
    /** Classics being downloaded, by Gutenberg id. */
    val downloading: Set<Int> = emptySet(),
    /** Classics downloaded this time: Gutenberg id → library book id. */
    val downloaded: Map<Int, Long> = emptyMap(),
    val message: String? = null,
)

/**
 * Free classics: searching Project Gutenberg and downloading a book's EPUB into the library (a new To read book,
 * or the matching book already there). A failed download leaves no new book behind.
 */
class ClassicsViewModel(
    private val source: ClassicsSource,
    private val matcher: EbookMatcher,
    private val ebooks: EbookRepository,
    private val books: BookRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ClassicsScreenState())
    val state: StateFlow<ClassicsScreenState> = _state.asStateFlow()
    private var searching: Job? = null

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        searching?.cancel()
        _state.update { it.copy(query = q, search = ClassicsState.Searching) }
        searching = viewModelScope.launch {
            val result = try {
                ClassicsState.Results(source.search(q))
            } catch (e: ClassicsUnavailable) {
                ClassicsState.Failed
            }
            _state.update { it.copy(search = result) }
        }
    }

    fun retry() = search(_state.value.query)

    fun download(classic: Classic) {
        if (classic.id in _state.value.downloading) return
        _state.update { it.copy(downloading = it.downloading + classic.id, message = null) }
        viewModelScope.launch {
            var created: Long? = null
            try {
                val match = matcher.match(EbookMetadata(classic.title, classic.authors, null, classic.languages.firstOrNull()), "${classic.title}.epub")
                val id = when (match) {
                    is Match.Existing -> match.book.id
                    is Match.New -> (books.save(match.book) as SaveResult.Saved).id.also { created = it }
                }
                // Already in the library with its file: downloading again would start it from the beginning.
                if (match is Match.New || !ebooks.fileOnPhone(id)) {
                    source.download(classic) { ebooks.add(id, EbookFormat.EPUB, fileName(classic), it) }
                }
                _state.update { it.copy(downloaded = it.downloaded + (classic.id to id)) }
            } catch (e: CancellationException) {
                // Leaving the screen mid-download: the new book goes too.
                withContext(NonCancellable) { created?.let { books.delete(it) } }
                throw e
            } catch (e: Exception) {
                created?.let { books.delete(it) }
                _state.update { it.copy(message = "Couldn't download the book. Check the connection and try again.") }
            } finally {
                _state.update { it.copy(downloading = it.downloading - classic.id) }
            }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** "Os Maias.epub", without characters file systems refuse. */
    private fun fileName(classic: Classic) =
        classic.title.substringBefore(':').replace(Regex("""[\\/:*?"<>|]"""), " ").trim().take(80).ifEmpty { "book" } + ".epub"
}
