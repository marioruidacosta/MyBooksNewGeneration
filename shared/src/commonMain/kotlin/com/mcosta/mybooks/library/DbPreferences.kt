package com.mcosta.mybooks.library

import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.data.db.SyncStateDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The preferences that belong to one library, so they travel with it instead of the device. */
val PER_LIBRARY_PREF_KEYS = setOf("home.pinnedTags", "suggestions.hidden")

/** Distinct from the sync engine's `prefs/<key>` time rows, which [load] must not read. */
private const val PREFIX = "pref:"

/** Writes one preference straight into a library database not open as a library (the upgrade); throws if it fails. */
suspend fun storeLibraryPreference(state: SyncStateDao, key: String, value: String) = state.set(PREFIX + key, value)

/**
 * [Preferences] kept in a library database's `sync_state` table under `pref:<key>`.
 * Reads come from a cache filled by [load]; [set] updates it at once and writes in the background.
 */
class DbPreferences(
    private val state: SyncStateDao,
    private val scope: CoroutineScope,
) : Preferences {
    private val cache = MutableStateFlow<Map<String, String>>(emptyMap())
    private val pending = MutableStateFlow<Map<String, String>>(emptyMap())
    private val writing = Mutex()

    /** Reads every stored preference into the cache; call once before first use. */
    suspend fun load() {
        val stored = state.prefs().associate { it.key.removePrefix(PREFIX) to it.value }
        // A set made before load finished wins over what was stored.
        cache.update { stored + it }
    }

    override fun get(key: String): String? = cache.value[key]

    /** Every key held, for preferences kept one key per item. */
    fun keys(): Set<String> = cache.value.keys

    override fun set(key: String, value: String) {
        cache.update { it + (key to value) }
        pending.update { it + (key to value) }
        // Drains run one at a time and write the latest pending values, so the last set wins.
        scope.launch { drain() }
    }

    /** Writes everything still pending; call before closing the database (works after [scope] is cancelled). */
    suspend fun flush() {
        withContext(NonCancellable) { drain() }
    }

    private suspend fun drain() {
        writing.withLock {
            while (true) {
                val batch = pending.value
                if (batch.isEmpty()) return
                for ((k, v) in batch) {
                    try {
                        state.set(PREFIX + k, v)
                    } catch (e: CancellationException) {
                        throw e // stays pending for flush()
                    } catch (_: Exception) {
                        return // best effort: stays pending
                    }
                    // Remove only what was written; a newer value for the key stays pending.
                    pending.update { if (it[k] == v) it - k else it }
                }
            }
        }
    }
}
