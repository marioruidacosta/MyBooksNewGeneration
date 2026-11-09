package com.mcosta.mybooks.data.suggestions

import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.data.authors.catalogTitleKey
import com.mcosta.mybooks.domain.searchKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How a suggested book is recognised: its ISBN, or its title (before any subtitle) and first author. */
fun suggestionKey(isbn: String?, title: String, authors: String): String =
    isbn?.takeIf { it.isNotBlank() } ?: "${catalogTitleKey(title)}|${searchKey(authors.substringBefore(',').trim())}"

/** Books marked "Not interested": never suggested again. Kept on the phone and in backups. */
class HiddenSuggestions(
    private val prefs: Preferences?,
    /** Told (with the preference key) after each change; used by sync. Runs in [scope]. */
    private val onChanged: (suspend (key: String) -> Unit)? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _keys = MutableStateFlow(parse(prefs?.get(KEY)))
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    fun hide(key: String) = addAll(listOf(key))

    /** Adds [keys] (e.g. from a backup) to those already hidden. */
    fun addAll(keys: Collection<String>) {
        val all = _keys.value + keys.filter { it.isNotBlank() }
        if (all == _keys.value) return
        _keys.value = all
        prefs?.set(KEY, stored())
        onChanged?.let { scope.launch { try { it(KEY) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* sync is best effort */ } } }
    }

    /** The value as kept and synced: one key per line, sorted. */
    fun stored(): String = _keys.value.sorted().joinToString("\n")

    /** The value from another device (null: removed there): shown at once and kept, but not reported as a change. */
    fun pulled(value: String?) {
        _keys.value = parse(value)
        prefs?.set(KEY, stored())
    }

    companion object {
        /** The preference's key, also its id in sync. */
        const val KEY = "suggestions.hidden"

        private fun parse(value: String?): Set<String> = value?.split('\n')?.filter { it.isNotBlank() }?.toSet().orEmpty()
    }
}
