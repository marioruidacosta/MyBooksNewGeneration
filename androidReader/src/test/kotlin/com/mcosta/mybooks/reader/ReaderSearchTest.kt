package com.mcosta.mybooks.reader

import com.mcosta.mybooks.domain.EbookFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSearchTest {
    private val scope = TestScope(UnconfinedTestDispatcher())

    /** Finds [count] matches, handed back [perBatch] at a time like Readium does chapter by chapter. */
    private class FakeSearch(val count: Int, val perBatch: Int = 7) : BookSearch {
        var given = 0
        var query = ""
        var closed = false
        override suspend fun close() {
            closed = true
        }
        override suspend fun start(query: String) {
            this.query = query
            given = 0
        }
        override suspend fun next(): List<SearchHit>? {
            if (given >= count) return null
            val batch = (given until minOf(count, given + perBatch)).map { SearchHit("{\"p\":$it}", "before $it ", query, " after", "Chapter") }
            given += batch.size
            return batch
        }
    }

    private fun vm(search: BookSearch) = ReaderViewModel(
        load = { ReaderFile(EbookFormat.EPUB, "/b.epub", null) },
        save = { _, _ -> false },
        markRead = {},
        scope = scope,
        bookSearch = search,
    )

    @Test
    fun searchResultsShowTheWordsAround() {
        val snippet = searchSnippet("A long text that goes on and on before the word ", "convento", " and then the text continues for quite a while longer.")
        assertEquals("…on and on before the word ", snippet.before)
        assertEquals("convento", snippet.match)
        assertEquals(" and then the text continues…", snippet.after)
        assertEquals(SearchSnippet("Era ", "Blimunda", " viu"), searchSnippet("Era\n", "Blimunda", " viu"))
    }

    @Test
    fun noResults() {
        val vm = vm(FakeSearch(0))
        vm.search("passarola")
        val s = vm.search.value
        assertEquals(emptyList<SearchHit>(), s.hits)
        assertTrue(s.done)
        assertFalse(s.searching)
    }

    @Test
    fun resultsComeTwentyAtATime() {
        val search = FakeSearch(45)
        val vm = vm(search)
        vm.search(" Blimunda ")
        assertEquals("Blimunda", search.query)
        assertEquals(21, vm.search.value.hits.size)          // whole chapters: 7 + 7 + 7
        assertFalse(vm.search.value.done)
        vm.searchMore()
        assertEquals(42, vm.search.value.hits.size)
        vm.searchMore()
        assertEquals(45, vm.search.value.hits.size)
        assertTrue(vm.search.value.done)
    }

    @Test
    fun aReopenedBookSearchesItsNewCopy() {
        // Recreating the reader opens the book again: search moves to the new copy and starts clean.
        val old = FakeSearch(3)
        val vm = vm(old)
        vm.search("a")
        val fresh = FakeSearch(1)
        vm.useSearch(fresh)
        assertTrue(old.closed)
        assertEquals(SearchState(), vm.search.value)
        vm.search("b")
        assertEquals(1, vm.search.value.hits.size)
        assertEquals("b", fresh.query)
    }

    @Test
    fun aNewSearchStartsOver() {
        val vm = vm(FakeSearch(3))
        vm.search("a")
        vm.search("b")
        assertEquals(listOf("b", "b", "b"), vm.search.value.hits.map { it.match })
    }
}
