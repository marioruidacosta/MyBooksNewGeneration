package com.mcosta.mybooks.physical

import com.mcosta.mybooks.data.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a removed bookcase's key holds: it stays listed, so that the removal reaches the other devices. */
private const val REMOVED = ""

/**
 * This library's bookcases, one synced preference each: `bookcase.<uuid>`. Two devices changing different bookcases
 * can't overwrite each other; for the same one, the newest change wins.
 */
class BookcaseStore(
    private val prefs: Preferences,
    /** Every key in [prefs]. */
    private val storedKeys: () -> Collection<String>,
    /** Told (with the key) after a change made on this device; used by sync. */
    private val onChanged: suspend (String) -> Unit,
    /** Writes a value to disk now, before it is queued. */
    private val persist: suspend (key: String, value: String) -> Unit = { _, _ -> },
) {
    private val _bookcases = MutableStateFlow(read())
    val bookcases: StateFlow<List<Bookcase>> = _bookcases.asStateFlow()

    /** The keys that sync, removed ones included. */
    val keys: List<String> get() = storedKeys().filter { it.startsWith(BOOKCASE_PREFIX) }

    /** The value as uploaded; null when the bookcase was removed. */
    fun value(key: String): String? = prefs.get(key)?.takeIf { it != REMOVED }

    private fun read(): List<Bookcase> =
        storedKeys().filter { it.startsWith(BOOKCASE_PREFIX) }.sorted().mapNotNull { key ->
            prefs.get(key)?.takeIf { it != REMOVED }?.let { parseBookcase(key, it) }
        }

    /** Reads the stored bookcases again; call once the preferences are loaded. */
    fun reload() {
        _bookcases.value = read()
    }

    /** A value from another device (null: removed there). Not reported as a change, or it would be pushed back. */
    fun pulled(key: String, value: String?) {
        prefs.set(key, value ?: REMOVED)
        reload()
    }

    /** A bookcase from a newer version of the app is never written back: it would lose what this one can't read. */
    suspend fun save(bookcase: Bookcase) {
        if (bookcase.readOnly) return
        record(BOOKCASE_PREFIX + bookcase.id, bookcase.toStored())
    }

    suspend fun remove(id: String) = record(BOOKCASE_PREFIX + id, REMOVED)

    /** From a backup: replaces a bookcase with the same id. An unreadable value is skipped. */
    suspend fun restore(id: String, value: String) {
        val key = BOOKCASE_PREFIX + id
        if (parseBookcase(key, value) != null) record(key, value)
    }

    /** From another library: a bookcase already here stays as it is. */
    suspend fun adoptIfMissing(id: String, value: String) {
        if (!holds(id)) restore(id, value)
    }

    /** True while this library has that bookcase. */
    fun holds(id: String): Boolean = value(BOOKCASE_PREFIX + id) != null

    /** It moved to another library. Not reported as a change: an account's copy in the cloud must stay. */
    suspend fun letGo(id: String) {
        val key = BOOKCASE_PREFIX + id
        prefs.set(key, REMOVED)
        persist(key, REMOVED)
        reload()
    }

    /** Each live bookcase's id and stored value, unreadable ones included (for backups and moves). */
    fun all(): List<Pair<String, String>> =
        keys.sorted().mapNotNull { key -> value(key)?.let { key.removePrefix(BOOKCASE_PREFIX) to it } }

    private suspend fun record(key: String, value: String) {
        prefs.set(key, value)
        persist(key, value)
        reload()
        onChanged(key)
    }
}
