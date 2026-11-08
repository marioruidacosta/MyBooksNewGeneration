package com.mcosta.mybooks.sync

import com.mcosta.mybooks.data.db.SyncQueueDao
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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

    /** Emits after each queued change; wakes the sync engine. */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    fun now(): Long = clock()

    /** Runs [block] atomically (the app passes a Room write transaction) and returns its result. */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> transaction(block: suspend () -> T): T {
        var result: Any? = null
        runTransaction { result = block() }
        return result as T
    }

    /** Queues ([table], [rowId]) for upload when sync is enabled; a row already queued stays queued once. */
    suspend fun record(table: SyncTable, rowId: String) {
        if (!enabled()) return
        queue.enqueue(table.wire, rowId, clock())
        _changes.tryEmit(Unit)
    }
}
