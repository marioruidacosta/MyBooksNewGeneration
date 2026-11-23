package com.mcosta.mybooks.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.SaveResult
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.data.lookup.failureReason
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What became of one scanned barcode. */
sealed interface ScanResult {
    data object Looking : ScanResult
    data class Added(val book: Book) : ScanResult
    /** Already in the library: nothing was added. */
    data class AlreadyHave(val book: Book) : ScanResult
    /** No book database knows it: it can be added by hand. */
    data object NotFound : ScanResult
    /** The book databases couldn't be reached. */
    data class Failed(val reason: String) : ScanResult
    /** Added, then taken back out with Undo. */
    data class Undone(val book: Book) : ScanResult
    /** Already in the library, now on the shelf being scanned; Undo puts it back as it was. */
    data class Placed(
        val book: Book,
        val previousShelf: String?,
        val previousPosition: Double?,
        val previousStatus: ReadingStatus,
    ) : ScanResult
    /** Already on the shelf being scanned: it stays where it is. */
    data class AlreadyOnShelf(val book: Book) : ScanResult
}

data class ScanItem(val isbn: String, val result: ScanResult)

data class BatchScanState(
    /** Newest scan first. */
    val items: List<ScanItem> = emptyList(),
    /** The shelf scanned books go to: To read or To buy. */
    val status: ReadingStatus = ReadingStatus.TO_READ,
) {
    val added: Int get() = items.count { it.result is ScanResult.Added }
    val placed: Int get() = items.count { it.result is ScanResult.Added || it.result is ScanResult.Placed }
}

/** Scanning several books in a row: each barcode is looked up and added straight away, with Undo per book. */
class BatchScanViewModel(
    private val repo: BookRepository,
    /** The physical shelf the books go on, left to right in the order scanned; null to just add them. */
    private val shelfId: String? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(BatchScanState())
    val state: StateFlow<BatchScanState> = _state.asStateFlow()

    val scanningOntoShelf: Boolean get() = shelfId != null

    /** One scan at a time onto a shelf, in order: a slow lookup must not swap two books. */
    private val inOrder = Mutex()

    fun setStatus(status: ReadingStatus) {
        _state.update { it.copy(status = status) }
    }

    /** A scanned ISBN; one already scanned this time is not looked up again (returns false). */
    fun scanned(isbn: String): Boolean {
        if (_state.value.items.any { it.isbn == isbn }) return false
        val status = _state.value.status
        _state.update { it.copy(items = listOf(ScanItem(isbn, ScanResult.Looking)) + it.items) }
        viewModelScope.launch { resolve(isbn, status) }
        return true
    }

    private suspend fun resolve(isbn: String, status: ReadingStatus) {
        val shelf = shelfId
        if (shelf == null) set(isbn, add(isbn, status))
        else inOrder.withLock { set(isbn, onShelf(add(isbn, ReadingStatus.TO_READ), shelf)) }
    }

    /** What was found, put at the end of [shelf]. A To buy book in your hands is now yours: it becomes To read. */
    private suspend fun onShelf(found: ScanResult, shelf: String): ScanResult = when (found) {
        is ScanResult.Added -> {
            repo.placeAtEnd(found.book.id, shelf)
            ScanResult.Added(repo.getBook(found.book.id) ?: found.book)
        }
        is ScanResult.AlreadyHave -> {
            val book = found.book
            if (book.shelfId == shelf && book.status != ReadingStatus.WISHLIST) {
                ScanResult.AlreadyOnShelf(book)
            } else {
                if (book.status == ReadingStatus.WISHLIST) repo.setStatus(book.id, ReadingStatus.TO_READ)
                repo.placeAtEnd(book.id, shelf)
                ScanResult.Placed(repo.getBook(book.id) ?: book, book.shelfId, book.shelfPosition, book.status)
            }
        }
        else -> found
    }

    private suspend fun add(isbn: String, status: ReadingStatus): ScanResult {
        repo.findByIsbn(isbn)?.let { return ScanResult.AlreadyHave(it) }
        return when (val found = repo.lookup(isbn)) {
            is LookupResult.Found -> when (val saved = repo.save(found.book.copy(id = 0, isbn = isbn, status = status))) {
                is SaveResult.Saved -> repo.getBook(saved.id)?.let { ScanResult.Added(it) } ?: ScanResult.NotFound
                SaveResult.DuplicateIsbn -> repo.findByIsbn(isbn)?.let { ScanResult.AlreadyHave(it) } ?: ScanResult.NotFound
                else -> ScanResult.NotFound
            }
            LookupResult.NotFound, is LookupResult.NotFoundWithErrors -> ScanResult.NotFound
            is LookupResult.Error -> ScanResult.Failed(failureReason(found.cause))
        }
    }

    /** Takes an added book back out of the library. */
    fun undo(isbn: String) {
        val item = _state.value.items.firstOrNull { it.isbn == isbn } ?: return
        when (val result = item.result) {
            is ScanResult.Added -> {
                set(isbn, ScanResult.Undone(result.book))
                viewModelScope.launch { repo.delete(result.book.id) }
            }
            is ScanResult.Placed -> {
                set(isbn, ScanResult.Undone(result.book))
                viewModelScope.launch {
                    if (result.book.status != result.previousStatus) repo.setStatus(result.book.id, result.previousStatus)
                    repo.setPlace(result.book.id, result.previousShelf, result.previousPosition)
                }
            }
            else -> Unit
        }
    }

    /** Looks a barcode up again (after the book databases couldn't be reached). */
    fun retry(isbn: String) {
        val item = _state.value.items.firstOrNull { it.isbn == isbn } ?: return
        if (item.result !is ScanResult.Failed) return
        val status = _state.value.status
        set(isbn, ScanResult.Looking)
        viewModelScope.launch { resolve(isbn, status) }
    }

    private fun set(isbn: String, result: ScanResult) {
        _state.update { s -> s.copy(items = s.items.map { if (it.isbn == isbn) it.copy(result = result) else it }) }
    }
}
