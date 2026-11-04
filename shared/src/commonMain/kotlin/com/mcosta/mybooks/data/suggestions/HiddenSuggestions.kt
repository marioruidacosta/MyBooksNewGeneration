package com.mcosta.mybooks.data.suggestions

import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.data.authors.catalogTitleKey
import com.mcosta.mybooks.domain.searchKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How a suggested book is recognised: its ISBN, or its title (before any subtitle) and first author. */
fun suggestionKey(isbn: String?, title: String, authors: String): String =
    isbn?.takeIf { it.isNotBlank() } ?: "${catalogTitleKey(title)}|${searchKey(authors.substringBefore(',').trim())}"

/** Books marked "Not interested": never suggested again. Kept on the phone and in backups. */
class HiddenSuggestions(private val prefs: Preferences?) {
    private val _keys = MutableStateFlow(prefs?.get(KEY)?.split('\n')?.filter { it.isNotBlank() }?.toSet().orEmpty())
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    fun hide(key: String) = addAll(listOf(key))

    /** Adds [keys] (e.g. from a backup) to those already hidden. */
    fun addAll(keys: Collection<String>) {
        val all = _keys.value + keys.filter { it.isNotBlank() }
        if (all == _keys.value) return
        _keys.value = all
        prefs?.set(KEY, all.sorted().joinToString("\n"))
    }

    private companion object {
        const val KEY = "suggestions.hidden"
    }
}
