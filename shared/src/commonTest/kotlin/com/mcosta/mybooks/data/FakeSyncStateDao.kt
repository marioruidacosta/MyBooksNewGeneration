package com.mcosta.mybooks.data

import com.mcosta.mybooks.data.db.SyncStateDao
import com.mcosta.mybooks.data.db.SyncStateEntity

/** In-memory [SyncStateDao]. */
class FakeSyncStateDao : SyncStateDao {
    val rows = mutableMapOf<String, String>()

    override suspend fun get(key: String): String? = rows[key]
    override suspend fun put(entry: SyncStateEntity) {
        rows[entry.key] = entry.value
    }
    override suspend fun clear() = rows.clear()
}
