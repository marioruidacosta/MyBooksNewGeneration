package com.mcosta.mybooks.data

import com.mcosta.mybooks.data.db.SyncQueueDao
import com.mcosta.mybooks.data.db.SyncQueueEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [SyncQueueDao]: one entry per (table, rowId), the first one kept. */
class FakeSyncQueueDao : SyncQueueDao {
    val rows = MutableStateFlow<List<SyncQueueEntity>>(emptyList())
    private var nextId = 1L

    override suspend fun enqueue(entry: SyncQueueEntity) {
        if (rows.value.none { it.table == entry.table && it.rowId == entry.rowId }) {
            rows.value = rows.value + entry.copy(id = nextId++)
        }
    }

    override suspend fun batch(limit: Int) = rows.value.take(limit)
    override suspend fun remove(ids: List<Long>) {
        rows.value = rows.value.filter { it.id !in ids }
    }
    override fun count(): Flow<Int> = rows.map { it.size }
    override suspend fun clear() {
        rows.value = emptyList()
    }
}
