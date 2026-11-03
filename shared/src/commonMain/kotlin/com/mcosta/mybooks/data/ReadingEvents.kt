package com.mcosta.mybooks.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Moments worth celebrating, announced by [BookRepository] and shown by whichever screen is open. */
class ReadingEvents {
    private val _finished = MutableSharedFlow<Long>(extraBufferCapacity = 8)

    /** Ids of books just marked as Read (not imported or restored ones). */
    val finished: SharedFlow<Long> = _finished.asSharedFlow()

    fun bookFinished(id: Long) {
        _finished.tryEmit(id)
    }
}
