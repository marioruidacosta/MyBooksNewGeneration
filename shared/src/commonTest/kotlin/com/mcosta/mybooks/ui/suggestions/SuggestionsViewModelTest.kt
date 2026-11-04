package com.mcosta.mybooks.ui.suggestions

import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.data.authors.AuthorBook
import com.mcosta.mybooks.data.suggestions.HiddenSuggestions
import com.mcosta.mybooks.data.suggestions.SuggestionEngine
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SuggestionsViewModelTest : MainDispatcherTest() {
    private class MemoryPreferences : Preferences {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String) { values[key] = value }
    }

    private var others = listOf(AuthorBook("Caim", "José Saramago", 2009, null, "9789722120005", null, null, null))
    private val engine = SuggestionEngine(
        library = { listOf(Book(title = "Memorial", authors = "José Saramago", rating = 5, status = ReadingStatus.READ)) },
        authorBooks = { others },
        dispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    )

    @Test
    fun notInterestedHidesForGoodAndRefreshFindsNewOnes() = runTest {
        val prefs = MemoryPreferences()
        val vm = SuggestionsViewModel(engine, HiddenSuggestions(prefs))
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        assertEquals(false, vm.state.value.loading)
        assertEquals(listOf("Caim"), vm.state.value.suggestions.mix().map { it.title })
        vm.hide("9789722120005")
        assertEquals(emptyList(), vm.state.value.suggestions.sections)
        assertEquals(setOf("9789722120005"), HiddenSuggestions(prefs).keys.value)        // remembered

        others = others + AuthorBook("Ensaio", "José Saramago", 1995, null, "9789722113922", null, null, null)
        vm.refresh()
        assertEquals(listOf("Ensaio"), vm.state.value.suggestions.mix().map { it.title })
    }

    @Test
    fun hiddenKeysAddUp() {
        val hidden = HiddenSuggestions(null)
        hidden.hide("a")
        hidden.addAll(listOf("b", " ", "a"))
        assertEquals(setOf("a", "b"), hidden.keys.value)
    }
}
