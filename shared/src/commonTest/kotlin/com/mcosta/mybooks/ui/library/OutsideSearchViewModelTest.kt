package com.mcosta.mybooks.ui.library

import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.data.authors.AuthorBook
import com.mcosta.mybooks.data.releases.AllMonths
import com.mcosta.mybooks.data.releases.MonthBooks
import com.mcosta.mybooks.data.releases.ReleaseBook
import com.mcosta.mybooks.data.releases.ReleaseHit
import com.mcosta.mybooks.data.releases.ReleasesUnavailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OutsideSearchViewModelTest : MainDispatcherTest() {
    private val cegueira = ReleaseBook("b1", "9789722036504", "Ensaio sobre a Cegueira", listOf("José Saramago"))
    private val blindness = AuthorBook("Blindness", "José Saramago", 1997, null, null, null, null, null)
    private val other = AuthorBook("Cegonhas", "Alguém", null, null, null, null, null, null)

    private var monthsCalls = 0
    private var monthsFail = false
    private var monthsLoaded: () -> AllMonths = { AllMonths(listOf(MonthBooks("2026-11", true, listOf(cegueira))), emptyList(), null) }
    private val onlineQueries = mutableListOf<String>()
    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    private var onlineFound: (String) -> List<AuthorBook> = { q -> if (q == "cegu") listOf(other) else listOf(blindness) }

    private fun vm() = OutsideSearchViewModel(
        releases = {
            monthsCalls++
            if (monthsFail) throw ReleasesUnavailable(notPublished = false)
            monthsLoaded()
        },
        online = { q ->
            onlineQueries += q
            gates[q]?.await()
            onlineFound(q)
        },
    )

    @Test
    fun waitsForThePause() = runTest {
        val vm = vm()
        vm.onQuery("cegueira", libraryHasMatch = false)
        advanceTimeBy(799)
        runCurrent()
        assertEquals(Section.Idle, vm.state.value.releases)
        assertFalse(vm.state.value.shown)
        assertTrue(onlineQueries.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertTrue(vm.state.value.shown)
        assertEquals(Section.Results(listOf(ReleaseHit("2026-11", cegueira))), vm.state.value.releases)
        assertEquals(Section.Results(listOf(blindness)), vm.state.value.online)
    }

    @Test
    fun onlyWhenTheLibraryHasNoMatch() = runTest {
        val vm = vm()
        vm.onQuery("cegueira", libraryHasMatch = true)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, monthsCalls)
        assertTrue(onlineQueries.isEmpty())
        assertFalse(vm.state.value.shown)
        vm.onQuery("cegueira", libraryHasMatch = false)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(vm.state.value.shown)
        vm.onQuery("cegueira", libraryHasMatch = true)        // the library now has it
        assertFalse(vm.state.value.shown)
    }

    @Test
    fun shortQueriesWaitUnlessIsbn() = runTest {
        val vm = vm()
        vm.onQuery("ab", libraryHasMatch = false)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(onlineQueries.isEmpty())
        assertFalse(vm.state.value.shown)
        vm.onQuery("972-20-3650-5", libraryHasMatch = false)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("972-20-3650-5"), onlineQueries)
        assertEquals("9789722036504", vm.state.value.isbn)
        assertEquals(Section.Results(listOf(ReleaseHit("2026-11", cegueira))), vm.state.value.releases)
    }

    @Test
    fun invalidIsbnHint() = runTest {
        val vm = vm()
        vm.onQuery("9789722036505", libraryHasMatch = false)
        assertTrue(vm.state.value.invalidIsbn)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("9789722036505"), onlineQueries)       // searched as text
        assertEquals(null, vm.state.value.isbn)
        vm.onQuery("cegueira", libraryHasMatch = false)
        assertFalse(vm.state.value.invalidIsbn)
    }

    /** Review focus: typing fast never shows an older query's results. */
    @Test
    fun staleResultsAreDropped() = runTest {
        val vm = vm()
        gates["cegu"] = CompletableDeferred()
        vm.onQuery("cegu", libraryHasMatch = false)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("cegu"), onlineQueries)
        vm.onQuery("cegueira", libraryHasMatch = false)
        advanceTimeBy(1_000)
        runCurrent()
        gates.getValue("cegu").complete(Unit)
        runCurrent()
        assertEquals(Section.Results(listOf(blindness)), vm.state.value.online)
    }

    /** Review focus: coming back from a result page recomposes with the same query: no new search, no flash. */
    @Test
    fun sameQueryDoesNotSearchAgain() = runTest {
        val vm = vm()
        vm.onQuery("cegueira", libraryHasMatch = false)
        advanceTimeBy(1_000)
        runCurrent()
        val before = vm.state.value
        vm.onQuery("cegueira", libraryHasMatch = false)
        assertEquals(before, vm.state.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("cegueira"), onlineQueries)
        assertEquals(before, vm.state.value)
    }

    /** Review focus: no network and no saved months; Retry works once it's back. Months load once. */
    @Test
    fun releasesFailThenRetry() = runTest {
        monthsFail = true
        val vm = vm()
        vm.onQuery("cegueira", libraryHasMatch = false)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(Section.Failed, vm.state.value.releases)
        assertEquals(Section.Results(listOf(blindness)), vm.state.value.online)
        monthsFail = false
        vm.retry(OutsideSection.RELEASES)
        runCurrent()
        assertEquals(Section.Results(listOf(ReleaseHit("2026-11", cegueira))), vm.state.value.releases)
        vm.onQuery("saramago", libraryHasMatch = false)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, monthsCalls)                                 // the failed load and the retry; not again
    }

    @Test
    fun onlineFailsAndRetries() = runTest {
        onlineFound = { throw IllegalStateException("offline") }
        val vm = vm()
        vm.onQuery("cegueira", libraryHasMatch = false)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(Section.Failed, vm.state.value.online)
        onlineFound = { listOf(blindness) }
        vm.retry(OutsideSection.ONLINE)
        runCurrent()
        assertEquals(Section.Results(listOf(blindness)), vm.state.value.online)
    }

    @Test
    fun emptyResults() = runTest {
        onlineFound = { emptyList() }
        val vm = vm()
        vm.onQuery("passarola", libraryHasMatch = false)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(Section.Empty, vm.state.value.releases)
        assertEquals(Section.Empty, vm.state.value.online)
    }

    /** A load with months missing (offline, or one timed out) is used, but loaded again for the next search. */
    @Test
    fun partialMonthsAreLoadedAgain() = runTest {
        monthsLoaded = { AllMonths(listOf(MonthBooks("2026-11", true, emptyList())), listOf("2026-10"), null) }
        val vm = vm()
        vm.onQuery("cegueira", libraryHasMatch = false)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(Section.Empty, vm.state.value.releases)
        monthsLoaded = { AllMonths(listOf(MonthBooks("2026-11", true, listOf(cegueira))), emptyList(), null) }
        vm.onQuery("saramago", libraryHasMatch = false)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(Section.Results(listOf(ReleaseHit("2026-11", cegueira))), vm.state.value.releases)
        vm.onQuery("blimunda", libraryHasMatch = false)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, monthsCalls)                                 // the complete load is kept
    }

    /** No month could be loaded: a failure with Retry, not "Nothing in New in Portugal". */
    @Test
    fun noMonthsLoadedIsAFailure() = runTest {
        monthsLoaded = { AllMonths(emptyList(), listOf("2026-11", "2026-10"), null) }
        val vm = vm()
        vm.onQuery("cegueira", libraryHasMatch = false)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(Section.Failed, vm.state.value.releases)
    }
}
