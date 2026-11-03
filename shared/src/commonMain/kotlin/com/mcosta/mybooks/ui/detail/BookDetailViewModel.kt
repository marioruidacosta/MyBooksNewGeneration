package com.mcosta.mybooks.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.MAX_REVIEW_LENGTH
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Book detail: status, rating and notes edits are saved immediately; the notes and review drafts are also
 * saved when the screen is left ([onCleared]) using [saveScope], which outlives the ViewModel.
 */
class BookDetailViewModel(
    private val repo: BookRepository,
    private val id: Long,
    private val saveScope: CoroutineScope,
) : ViewModel() {
    private val _notesDraft = MutableStateFlow("")
    val notesDraft: StateFlow<String> = _notesDraft.asStateFlow()

    /** Stored notes the draft was last in sync with; null until the book has loaded. */
    private var syncedNotes: String? = null
    private val hasUnsavedNotes get() = syncedNotes != null && _notesDraft.value != syncedNotes

    private val _reviewDraft = MutableStateFlow("")
    /** The public review being typed (at most [MAX_REVIEW_LENGTH] characters). */
    val reviewDraft: StateFlow<String> = _reviewDraft.asStateFlow()
    private var syncedReview: String? = null
    private val hasUnsavedReview get() = syncedReview != null && _reviewDraft.value != syncedReview

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    val book: StateFlow<Book?> = repo.observeBook(id)
        .onEach { b ->
            // Follow notes changed elsewhere (e.g. the Edit screen) unless the user has unsaved typing.
            if (b != null && b.notes != syncedNotes) {
                if (!hasUnsavedNotes) _notesDraft.value = b.notes
                syncedNotes = b.notes
            }
            if (b != null && b.review != syncedReview) {
                if (!hasUnsavedReview) _reviewDraft.value = b.review
                syncedReview = b.review
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** "Don't share this book". */
    val shareExcluded: StateFlow<Boolean> = repo.observeShareExcluded(id).stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setShareExcluded(excluded: Boolean) {
        viewModelScope.launch { repo.setShareExcluded(id, excluded) }
    }

    fun setStatus(status: ReadingStatus) {
        viewModelScope.launch { repo.setStatus(id, status) }
    }

    fun rate(rating: Int?) {
        viewModelScope.launch { repo.setRating(id, rating) }
    }

    private val _progressError = MutableStateFlow<String?>(null)
    val progressError: StateFlow<String?> = _progressError.asStateFlow()

    /** Updates reading progress from typed [input]: a page number, or a percentage when [byPages] is false. */
    fun updateProgress(input: String, byPages: Boolean) {
        val progress = parseProgress(input, byPages)
        _progressError.value = (progress as? ProgressInput.Invalid)?.message
        viewModelScope.launch {
            when (progress) {
                is ProgressInput.Page -> repo.setProgressPages(id, progress.page)
                is ProgressInput.Percent -> repo.setProgressPercent(id, progress.percent)
                is ProgressInput.Invalid -> Unit
            }
        }
    }

    fun setCover(coverRef: String) {
        viewModelScope.launch { repo.setCover(id, coverRef) }
    }

    fun setDateStarted(date: Long) {
        viewModelScope.launch { repo.setDateStarted(id, date) }
    }

    fun setDateRead(date: Long) {
        viewModelScope.launch { repo.setDateRead(id, date) }
    }

    fun editNotes(text: String) {
        _notesDraft.value = text
    }

    fun saveNotes() {
        if (_deleted.value || !hasUnsavedNotes) return
        val draft = _notesDraft.value
        syncedNotes = draft
        saveScope.launch { repo.setNotes(id, draft) }
    }

    fun editReview(text: String) {
        _reviewDraft.value = text.take(MAX_REVIEW_LENGTH)
    }

    fun saveReview() {
        if (_deleted.value || !hasUnsavedReview) return
        val draft = _reviewDraft.value
        syncedReview = draft
        saveScope.launch { repo.setReview(id, draft) }
    }

    fun delete() {
        _deleted.value = true
        viewModelScope.launch { repo.delete(id) }
    }

    override fun onCleared() {
        saveNotes()
        saveReview()
    }
}
