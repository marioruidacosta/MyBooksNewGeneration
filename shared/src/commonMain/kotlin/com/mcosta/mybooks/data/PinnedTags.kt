package com.mcosta.mybooks.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tags shown as rows on the home screen, in the order pinned. Kept on the phone. */
class PinnedTags(private val prefs: Preferences?) {
    private val _tags = MutableStateFlow(prefs?.get(KEY)?.split('\n')?.filter { it.isNotBlank() }.orEmpty())
    val tags: StateFlow<List<String>> = _tags.asStateFlow()

    fun isPinned(tag: String): Boolean = _tags.value.any { it.equals(tag, ignoreCase = true) }

    fun setPinned(tag: String, pinned: Boolean) {
        val others = _tags.value.filterNot { it.equals(tag, ignoreCase = true) }
        val all = if (pinned) others + tag.trim() else others
        if (all == _tags.value) return
        _tags.value = all
        prefs?.set(KEY, all.joinToString("\n"))
    }

    private companion object {
        const val KEY = "home.pinnedTags"
    }
}
