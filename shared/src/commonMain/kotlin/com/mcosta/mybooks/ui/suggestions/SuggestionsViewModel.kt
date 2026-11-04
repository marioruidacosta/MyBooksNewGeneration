package com.mcosta.mybooks.ui.suggestions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.data.suggestions.HiddenSuggestions
import com.mcosta.mybooks.data.suggestions.SuggestionEngine
import com.mcosta.mybooks.data.suggestions.Suggestions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SuggestionsState(
    /** Working them out (first time or refreshing). */
    val loading: Boolean = true,
    /** Without the books marked "Not interested". */
    val suggestions: Suggestions = Suggestions.NONE,
)

/** Suggestions for Home's row and the Suggestions screen (the engine keeps them until refreshed). */
class SuggestionsViewModel(private val engine: SuggestionEngine, private val hidden: HiddenSuggestions) : ViewModel() {
    private val found = MutableStateFlow<Suggestions?>(null)
    private val refreshing = MutableStateFlow(false)

    val state: StateFlow<SuggestionsState> = combine(found, refreshing, hidden.keys) { s, r, h ->
        SuggestionsState(loading = s == null || r, suggestions = s?.without(h) ?: Suggestions.NONE)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SuggestionsState())

    init {
        viewModelScope.launch { found.value = engine.suggestions() }
    }

    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                found.value = engine.suggestions(refresh = true)
            } finally {
                refreshing.value = false
            }
        }
    }

    /** "Not interested": never suggested again. */
    fun hide(key: String) = hidden.hide(key)
}
