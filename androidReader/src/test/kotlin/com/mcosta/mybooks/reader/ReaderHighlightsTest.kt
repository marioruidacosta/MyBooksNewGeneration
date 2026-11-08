package com.mcosta.mybooks.reader

import com.mcosta.mybooks.domain.EbookFormat
import com.mcosta.mybooks.domain.Quote
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderHighlightsTest {
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val quotes = MutableStateFlow(
        listOf(
            Quote(1, 9, "Typed by hand.", page = 12),
            Quote(2, 9, "Highlighted.", position = "{\"p\":2}"),
        ),
    )
    private val added = mutableListOf<Pair<String, String>>()
    private val deleted = mutableListOf<Long>()

    private val vm = ReaderViewModel(
        load = { ReaderFile(EbookFormat.EPUB, "/b.epub", null) },
        save = { _, _ -> false },
        markRead = {},
        scope = scope,
        quotes = quotes,
        addHighlight = { text, position -> added += text to position },
        deleteQuote = { deleted += it },
    )

    @Test
    fun highlightBecomesQuote() {
        vm.highlight("  The spice must flow. ", "{\"p\":5}")
        assertEquals(listOf("The spice must flow." to "{\"p\":5}"), added)
        vm.highlight("   ", "{\"p\":6}")
        assertEquals(1, added.size)
    }

    @Test
    fun typedQuotesAreNotDrawn() {
        assertEquals(listOf(2L), vm.highlights.value.map { it.id })
    }

    @Test
    fun tappingAHighlightShowsItAndDeletingRemovesIt() {
        vm.showHighlight(2)
        assertEquals("Highlighted.", vm.shownHighlight.value?.text)
        vm.deleteShownHighlight()
        assertEquals(listOf(2L), deleted)
        assertNull(vm.shownHighlight.value)
    }

    @Test
    fun anUnknownHighlightShowsNothing() {
        vm.showHighlight(99)
        assertNull(vm.shownHighlight.value)
    }
}
