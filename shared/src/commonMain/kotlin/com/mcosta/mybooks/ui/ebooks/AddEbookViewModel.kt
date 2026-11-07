package com.mcosta.mybooks.ui.ebooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.SaveResult
import com.mcosta.mybooks.data.ebooks.EbookMatcher
import com.mcosta.mybooks.data.ebooks.EbookRepository
import com.mcosta.mybooks.data.ebooks.Match
import com.mcosta.mybooks.domain.EbookFormat
import com.mcosta.mybooks.domain.EbookMetadata
import com.mcosta.mybooks.platform.IncomingFile
import com.mcosta.mybooks.platform.Inspection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.use

/** Where adding an ebook file has got to. */
sealed interface AddEbookState {
    data object Checking : AddEbookState
    /** A readable file and the book it goes with; [replaces] when that book already has an ebook file. */
    data class Ready(val match: Match, val format: EbookFormat, val replaces: Boolean) : AddEbookState
    data class AdobeProtected(val match: Match) : AddEbookState
    data class Protected(val match: Match) : AddEbookState
    data object Unreadable : AddEbookState
    data object Working : AddEbookState
    data class Done(val bookId: Long, val read: Boolean) : AddEbookState
    data class Failed(val message: String) : AddEbookState
}

/**
 * Adding an ebook [file] to the library: it is checked ([inspect]), matched to a book (or to the book [forBookId],
 * when added from its page), then stored with **Read now** or **Add to To read** — or, when protected, the book is
 * added without its file. The temporary copy is deleted once done or when the screen closes.
 */
class AddEbookViewModel(
    private val file: IncomingFile,
    inspect: suspend (IncomingFile) -> Inspection,
    private val matcher: EbookMatcher,
    private val ebooks: EbookRepository,
    private val books: BookRepository,
    private val fs: FileSystem,
    private val forBookId: Long? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<AddEbookState>(AddEbookState.Checking)
    val state: StateFlow<AddEbookState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = try {
                when (val inspection = inspect(file)) {
                    is Inspection.Readable -> {
                        val match = matchFor(inspection.metadata)
                        AddEbookState.Ready(match, inspection.format, replaces = match is Match.Existing && ebooks.hasEbook(match.book.id))
                    }
                    is Inspection.AdobeProtected -> AddEbookState.AdobeProtected(matchFor(inspection.metadata))
                    Inspection.Protected -> AddEbookState.Protected(matchFor(null))
                    Inspection.Unreadable -> AddEbookState.Unreadable
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AddEbookState.Unreadable
            }
        }
    }

    private suspend fun matchFor(meta: EbookMetadata?): Match {
        forBookId?.let { id -> books.getBook(id)?.let { return Match.Existing(it) } }
        return matcher.match(meta ?: EbookMetadata(null, emptyList(), null, null), file.fileName)
    }

    /** Stores the file and opens it: a To read book becomes Reading. */
    fun readNow() = store(read = true)

    /** Stores the file; the book keeps its status (a new one is To read). */
    fun addToRead() = store(read = false)

    private fun store(read: Boolean) {
        val ready = _state.value as? AddEbookState.Ready ?: return
        _state.value = AddEbookState.Working
        viewModelScope.launch {
            _state.value = try {
                val id = bookIdFor(ready.match)
                fs.source(file.path.toPath()).use { ebooks.add(id, ready.format, file.fileName, it) }
                if (read) ebooks.opened(id)
                AddEbookState.Done(id, read)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AddEbookState.Failed("Couldn't add the book: ${e.message ?: "unknown error"}")
            } finally {
                cleanUp()
            }
        }
    }

    /** For a protected file: the book is added (or kept) without its file, so reading can still be tracked. */
    fun addWithoutFile() {
        val match = when (val s = _state.value) {
            is AddEbookState.AdobeProtected -> s.match
            is AddEbookState.Protected -> s.match
            else -> return
        }
        _state.value = AddEbookState.Working
        viewModelScope.launch {
            _state.value = AddEbookState.Done(bookIdFor(match), read = false)
            cleanUp()
        }
    }

    private suspend fun bookIdFor(match: Match): Long = when (match) {
        is Match.Existing -> match.book.id
        is Match.New -> when (val saved = books.save(match.book)) {
            is SaveResult.Saved -> saved.id
            SaveResult.DuplicateIsbn -> books.findByIsbn(match.book.isbn!!)!!.id
            else -> (books.save(match.book.copy(isbn = null)) as SaveResult.Saved).id
        }
    }

    private fun cleanUp() = fs.delete(file.path.toPath(), mustExist = false)

    override fun onCleared() = cleanUp()
}
