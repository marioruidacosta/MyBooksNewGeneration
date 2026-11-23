package com.mcosta.mybooks.ui.physical

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.physical.Bookcase
import com.mcosta.mybooks.physical.BookcaseStore
import com.mcosta.mybooks.physical.MAX_BOOKCASE_NAME
import com.mcosta.mybooks.physical.ShelfPlace
import com.mcosta.mybooks.physical.byRoom
import com.mcosta.mybooks.physical.findShelf
import com.mcosta.mybooks.physical.onShelf
import com.mcosta.mybooks.physical.shelfIds
import com.mcosta.mybooks.physical.withoutColumn
import com.mcosta.mybooks.physical.withoutPlace
import com.mcosta.mybooks.physical.withoutShelf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PhysicalState(
    val bookcases: List<Bookcase> = emptyList(),
    val rooms: List<Pair<String, List<Bookcase>>> = emptyList(),
    val books: List<Book> = emptyList(),
    val withoutPlace: List<Book> = emptyList(),
    val ebookIds: Set<Long> = emptySet(),
    val message: String? = null,
    val undo: (() -> Unit)? = null,
    /** Changes with every new message, even one with the same words. */
    val noteId: Long = 0,
)

private data class Note(val id: Long = 0, val message: String? = null, val undo: (() -> Unit)? = null)

/** The bookcases of the physical library, and where the books are on them. Furniture never deletes a book. */
class PhysicalLibraryViewModel(
    private val repo: BookRepository,
    private val store: BookcaseStore,
    /** Outlives the screen, so that a save or a delete is not cut short when it closes; null in tests that don't need it. */
    private val writes: CoroutineScope? = null,
) : ViewModel() {
    private val note = MutableStateFlow(Note())
    private val writeScope: CoroutineScope get() = writes ?: viewModelScope

    val state: StateFlow<PhysicalState> = combine(
        repo.observeBooks(null, ""), store.bookcases, repo.observeEbookBookIds(), note,
    ) { books, bookcases, ebookIds, note ->
        PhysicalState(
            bookcases = bookcases,
            rooms = bookcases.byRoom(),
            books = books,
            withoutPlace = books.withoutPlace(bookcases, ebookIds),
            ebookIds = ebookIds,
            message = note.message,
            undo = note.undo,
            noteId = note.id,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PhysicalState())

    fun booksOn(shelfId: String): List<Book> = state.value.books.onShelf(shelfId)

    /** How many books removing these shelves would send to Without a place. */
    fun countOn(shelfIds: Collection<String>): Int {
        val shelves = shelfIds.toSet()
        return state.value.books.count { it.shelfId in shelves && it.status != ReadingStatus.WISHLIST }
    }

    fun bookcase(id: String): Bookcase? = store.bookcases.value.firstOrNull { it.id == id }

    /** Where that shelf is; null when it no longer exists. */
    fun place(shelfId: String): ShelfPlace? = store.bookcases.value.findShelf(shelfId)

    /** Saves it; the books on the shelves in [freed] (removed while editing) go to Without a place. */
    fun saveBookcase(bookcase: Bookcase, freed: Set<String> = emptySet()) {
        writeScope.launch {
            if (freed.isNotEmpty()) repo.clearPlaces(freed)
            store.save(bookcase)
        }
    }

    /** Every bookcase in that room moves to the new name; capitals don't matter in the old one. */
    fun renameRoom(from: String, to: String) {
        val name = to.trim().take(MAX_BOOKCASE_NAME)
        if (name.isEmpty()) return
        writeScope.launch {
            state.value.bookcases
                .filter { it.room.equals(from.trim(), ignoreCase = true) && !it.readOnly }
                .forEach { store.save(it.copy(room = name)) }
        }
    }

    fun removeBookcase(id: String) {
        val case = store.bookcases.value.firstOrNull { it.id == id } ?: return
        writeScope.launch {
            repo.clearPlaces(case.shelfIds().toSet())
            store.remove(id)
        }
    }

    fun removeShelf(bookcase: Bookcase, shelfId: String) {
        val next = bookcase.withoutShelf(shelfId)
        if (next == bookcase) return
        writeScope.launch {
            repo.clearPlaces(setOf(shelfId))
            store.save(next)
        }
    }

    fun removeColumn(bookcase: Bookcase, column: Int) {
        val next = bookcase.withoutColumn(column)
        if (next == bookcase) return
        val shelves = bookcase.columns[column].shelves.toSet()
        writeScope.launch {
            repo.clearPlaces(shelves)
            store.save(next)
        }
    }

    /** Puts these books at the end of the shelf, in this order, with Undo. */
    fun place(bookIds: List<Long>, shelfId: String) {
        if (bookIds.isEmpty()) return
        val before = bookIds.mapNotNull { id -> state.value.books.firstOrNull { it.id == id } }
        val label = state.value.bookcases.findShelf(shelfId)?.label
        writeScope.launch {
            bookIds.forEach { repo.placeAtEnd(it, shelfId) }
            val where = label?.let { "Shelf $it" } ?: "the shelf"
            val what = if (bookIds.size == 1) "Placed" else "${bookIds.size} books placed"
            say("$what on $where", undoOf(before))
        }
    }

    fun takeOff(bookId: Long) {
        val book = state.value.books.firstOrNull { it.id == bookId } ?: return
        writeScope.launch {
            repo.setPlace(bookId, null, null)
            say("Taken off the shelf", undoOf(listOf(book)))
        }
    }

    /** Moves a book to [toIndex] among the shelf's books, counted without it. */
    fun moveBook(bookId: Long, shelfId: String, toIndex: Int) {
        val others = booksOn(shelfId).filter { it.id != bookId }
        val index = toIndex.coerceIn(0, others.size)
        writeScope.launch {
            repo.moveOnShelf(bookId, shelfId, others.getOrNull(index - 1)?.id, others.getOrNull(index)?.id)
        }
    }

    /** Clears message [id]; a newer one stays. */
    fun dismissMessage(id: Long = note.value.id) {
        note.update { if (it.id == id) it.copy(message = null, undo = null) else it }
    }

    private fun say(message: String, undo: () -> Unit) {
        note.update { Note(it.id + 1, message, undo) }
    }

    private fun undoOf(books: List<Book>): () -> Unit = {
        dismissMessage()
        writeScope.launch {
            books.forEach { repo.setPlace(it.id, it.shelfId, it.shelfPosition) }
        }
    }
}
