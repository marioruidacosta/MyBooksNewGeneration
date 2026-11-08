package com.mcosta.mybooks.sync

import com.mcosta.mybooks.data.db.SyncQueueDao
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Marks the coroutines of one open transaction, and whether anything was queued during it. */
private class InTransaction : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<InTransaction>

    var recorded = false
}

/**
 * Queues rows for upload after a local change. Field times are stamped by the wrappers whether or not sync is
 * [enabled]; only queueing (and soft delete) depends on it.
 */
class SyncRecorder(
    private val queue: SyncQueueDao,
    private val clock: () -> Long,
    val enabled: () -> Boolean,
    private val runTransaction: suspend (suspend () -> Unit) -> Unit,
) {
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits after queued changes have been committed; wakes the sync engine. */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    fun now(): Long = clock()

    /**
     * Runs [block] atomically (the app passes a Room write transaction) and returns its result. A call nested in
     * another transaction joins it. [changes] emits once, after the outermost transaction has committed, if
     * anything was recorded; nothing is emitted when the block throws.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> transaction(block: suspend () -> T): T {
        if (coroutineContext[InTransaction] != null) return block()
        val marker = InTransaction()
        var result: Any? = null
        runTransaction { withContext(marker) { result = block() } }
        if (marker.recorded) _changes.tryEmit(Unit)
        return result as T
    }

    /** Queues ([table], [rowId]) for upload when sync is enabled; a row already queued stays queued once. */
    suspend fun record(table: SyncTable, rowId: String) {
        if (!enabled()) return
        queue.enqueue(table.wire, rowId, clock())
        val marker = coroutineContext[InTransaction]
        if (marker != null) marker.recorded = true else _changes.tryEmit(Unit)
    }
}
