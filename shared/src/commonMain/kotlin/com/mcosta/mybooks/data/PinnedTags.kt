package com.mcosta.mybooks.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Tags shown as rows on the home screen, in the order pinned. Kept on the phone. */
class PinnedTags(
    private val prefs: Preferences?,
    /** Told (with the preference key) after each change; used by sync. Runs in [scope]. */
    private val onChanged: (suspend (key: String) -> Unit)? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _tags = MutableStateFlow(parse(prefs?.get(KEY)))
    val tags: StateFlow<List<String>> = _tags.asStateFlow()

    fun isPinned(tag: String): Boolean = _tags.value.any { it.equals(tag, ignoreCase = true) }

    fun setPinned(tag: String, pinned: Boolean) {
        val others = _tags.value.filterNot { it.equals(tag, ignoreCase = true) }
        val all = if (pinned) others + tag.trim() else others
        if (all == _tags.value) return
        _tags.value = all
        prefs?.set(KEY, stored())
        onChanged?.let { scope.launch { try { it(KEY) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* sync is best effort */ } } }
    }

    /** The value as kept and synced: one tag per line, in the order pinned. */
    fun stored(): String = _tags.value.joinToString("\n")

    /** The value from another device (null: removed there): shown at once and kept, but not reported as a change. */
    fun pulled(value: String?) {
        _tags.value = parse(value)
        prefs?.set(KEY, stored())
    }

    companion object {
        /** The preference's key, also its id in sync. */
        const val KEY = "home.pinnedTags"

        private fun parse(value: String?): List<String> = value?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
    }
}
