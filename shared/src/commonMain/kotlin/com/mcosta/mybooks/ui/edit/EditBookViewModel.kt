package com.mcosta.mybooks.ui.edit

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

/** Editable text fields of a book; numbers are kept as typed and parsed on save. */
data class BookForm(
    val title: String = "",
    val authors: String = "",
    val isbn: String = "",
    val publisher: String = "",
    val year: String = "",
    val pages: String = "",
    val coverUrl: String = "",
    val description: String = "",
    val status: ReadingStatus = ReadingStatus.TO_READ,
    val rating: Int? = null,
    val notes: String = "",
    /** Public review (shown in the form only while signed in to the community). */
    val review: String = "",
    val series: String = "",
    /** The place in the series, as written ("1", "2.5"). */
    val seriesNumber: String = "",
)

data class EditState(
    val form: BookForm = BookForm(),
    val loading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val saving: Boolean = false,
    val savedId: Long? = null,
)

/** Add a new book ([bookId] null, optionally pre-filled by looking up [isbn]) or edit an existing one. */
class EditBookViewModel(
    private val repo: BookRepository,
    private val bookId: Long?,
    isbn: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(EditState())
    val state: StateFlow<EditState> = _state.asStateFlow()

    /** The stored book being edited; keeps fields the form doesn't show (dates). */
    private var original: Book? = null

    init {
        when {
            bookId != null -> viewModelScope.launch {
                val book = repo.getBook(bookId)
                original = book
                if (book != null) _state.update { it.copy(form = book.toForm()) }
            }
            isbn != null -> lookup(isbn)
        }
    }

    private fun lookup(isbn: String) {
        _state.update { it.copy(form = it.form.copy(isbn = isbn), loading = true) }
        viewModelScope.launch {
            val result = repo.lookup(isbn)
            _state.update { s ->
                when (result) {
                    is LookupResult.Found -> s.copy(loading = false, form = result.book.toForm().copy(isbn = isbn))
                    LookupResult.NotFound -> s.copy(loading = false, message = "Book not found — enter the details manually")
                    is LookupResult.NotFoundWithErrors -> s.copy(
                        loading = false,
                        message = "Book not found, and one book database couldn't be checked " +
                            "(${failureReason(result.cause)}) — enter the details manually",
                    )
                    is LookupResult.Error -> s.copy(
                        loading = false,
                        message = "Couldn't reach the book databases (${failureReason(result.cause)}) " +
                            "— enter the details manually",
                    )
                }
            }
        }
    }

    fun update(transform: (BookForm) -> BookForm) {
        _state.update { it.copy(form = transform(it.form), error = null) }
    }

    fun save() {
        // Ignore repeat taps: a second insert would duplicate a book without an ISBN.
        if (_state.value.saving || _state.value.savedId != null) return
        _state.update { it.copy(saving = true) }
        val f = _state.value.form
        val base = original ?: Book(title = "")
        val book = base.copy(
            title = f.title,
            authors = f.authors.trim(),
            isbn = f.isbn.trim().ifEmpty { null },
            publisher = f.publisher.trim().ifEmpty { null },
            publishedYear = f.year.trim().toIntOrNull(),
            pageCount = f.pages.trim().toIntOrNull(),
            coverUrl = f.coverUrl.trim().ifEmpty { null },
            description = f.description.trim().ifEmpty { null },
            status = f.status,
            rating = f.rating,
            notes = f.notes,
            review = f.review,
            series = f.series.trim().ifEmpty { null },
            seriesNumber = f.seriesNumber.trim().ifEmpty { null },
        )
        viewModelScope.launch {
            val error = when (val r = repo.save(book)) {
                is SaveResult.Saved -> {
                    _state.update { it.copy(savedId = r.id, error = null, saving = false) }
                    return@launch
                }
                SaveResult.InvalidTitle -> "Title is required"
                SaveResult.DuplicateIsbn -> "This ISBN is already in your library"
                SaveResult.InvalidIsbn -> "Invalid ISBN"
                SaveResult.InvalidRating -> "Rating must be 1–5"
                SaveResult.InvalidReview -> "Review is too long (2000 characters max)"
            }
            _state.update { it.copy(error = error, saving = false) }
        }
    }
}

private fun Book.toForm() = BookForm(
    title = title,
    authors = authors,
    isbn = isbn.orEmpty(),
    publisher = publisher.orEmpty(),
    year = publishedYear?.toString().orEmpty(),
    pages = pageCount?.toString().orEmpty(),
    coverUrl = coverUrl.orEmpty(),
    description = description.orEmpty(),
    status = status,
    rating = rating,
    notes = notes,
    review = review,
    series = series.orEmpty(),
    seriesNumber = seriesNumber.orEmpty(),
)
