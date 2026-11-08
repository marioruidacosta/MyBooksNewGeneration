package com.mcosta.mybooks.ui.library

import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.data.authors.AuthorBook
import com.mcosta.mybooks.data.releases.AllMonths
import com.mcosta.mybooks.data.releases.MonthBooks
import com.mcosta.mybooks.data.releases.ReleaseBook
import com.mcosta.mybooks.data.releases.ReleaseHit
import com.mcosta.mybooks.data.releases.ReleasesUnavailable
import com.mcosta.mybooks.domain.Book
import kotlinx.coroutines.flow.MutableStateFlow
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
    private var monthsGate: CompletableDeferred<Unit>? = null
    private var monthsLoaded: () -> AllMonths = { AllMonths(listOf(MonthBooks("2026-11", true, listOf(cegueira))), emptyList(), null) }
    private val library = MutableStateFlow<List<Book>>(emptyList())
    private val onlineQueries = mutableListOf<String>()
    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    private var onlineFound: (String) -> List<AuthorBook> = { q -> if (q == "cegu") listOf(other) else listOf(blindness) }

    private fun vm() = OutsideSearchViewModel(
        releases = { progress ->
            monthsCalls++
            monthsGate?.let { progress(3, 7); it.await() }
            if (monthsFail) throw ReleasesUnavailable(notPublished = false)
            monthsLoaded()
        },
        online = { q ->
            onlineQueries += q
            gates[q]?.await()
            onlineFound(q)
        },
        library = library,
        compute = mainDispatcher,
    )

    @Test
    fun waitsForThePause() = runTest {
        val vm = vm()
        vm.onQuery("cegueira")
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

    /** Search everywhere: the outside sources are searched even when the library has matches. */
    @Test
    fun searchesEvenWhenTheLibraryHasMatches() = runTest {
        library.value = listOf(Book(title = "Ensaio sobre a Cegueira", authors = "José Saramago"))
        val vm = vm()
        vm.onQuery("cegueira")
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(vm.state.value.shown)
        assertEquals(1, monthsCalls)
        assertEquals(listOf("cegueira"), onlineQueries)
    }

    /** Outside results already in the library (same ISBN, or same title and first author) are marked. */
    @Test
    fun marksBooksAlreadyInTheLibrary() = runTest {
        library.value = listOf(
            Book(title = "Ensaio sobre a Cegueira (edição de bolso)", authors = "Saramago", isbn = "9789722036504"),
            Book(title = "Blindness", authors = "Jose Saramago"),
        )
        val vm = vm()
        vm.onQuery("cegueira")
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(vm.state.value.owns(ReleaseHit("2026-11", cegueira)))
        assertTrue(vm.state.value.owns(blindness))
        assertFalse(vm.state.value.owns(other))
        library.value = emptyList()
        runCurrent()
        assertFalse(vm.state.value.owns(blindness))
    }

    @Test
    fun shortQueriesWaitUnlessIsbn() = runTest {
        val vm = vm()
        vm.onQuery("ab")
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(onlineQueries.isEmpty())
        assertFalse(vm.state.value.shown)
        vm.onQuery("972-20-3650-5")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("972-20-3650-5"), onlineQueries)
        assertEquals("9789722036504", vm.state.value.isbn)
        assertEquals(Section.Results(listOf(ReleaseHit("2026-11", cegueira))), vm.state.value.releases)
    }

    @Test
    fun invalidIsbnHint() = runTest {
        val vm = vm()
        vm.onQuery("9789722036505")
        assertTrue(vm.state.value.invalidIsbn)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("9789722036505"), onlineQueries)       // searched as text
        assertEquals(null, vm.state.value.isbn)
        vm.onQuery("cegueira")
        assertFalse(vm.state.value.invalidIsbn)
    }

    /** Review focus: typing fast never shows an older query's results. */
    @Test
    fun staleResultsAreDropped() = runTest {
        val vm = vm()
        gates["cegu"] = CompletableDeferred()
        vm.onQuery("cegu")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("cegu"), onlineQueries)
        vm.onQuery("cegueira")
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
        vm.onQuery("cegueira")
        advanceTimeBy(1_000)
        runCurrent()
        val before = vm.state.value
        vm.onQuery("cegueira")
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
        vm.onQuery("cegueira")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(Section.Failed, vm.state.value.releases)
        assertEquals(Section.Results(listOf(blindness)), vm.state.value.online)
        monthsFail = false
        vm.retry(OutsideSection.RELEASES)
        runCurrent()
        assertEquals(Section.Results(listOf(ReleaseHit("2026-11", cegueira))), vm.state.value.releases)
        vm.onQuery("saramago")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, monthsCalls)                                 // the failed load and the retry; not again
    }

    @Test
    fun onlineFailsAndRetries() = runTest {
        onlineFound = { throw IllegalStateException("offline") }
        val vm = vm()
        vm.onQuery("cegueira")
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
        vm.onQuery("passarola")
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
        vm.onQuery("cegueira")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(Section.Empty, vm.state.value.releases)
        monthsLoaded = { AllMonths(listOf(MonthBooks("2026-11", true, listOf(cegueira))), emptyList(), null) }
        vm.onQuery("saramago")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(Section.Results(listOf(ReleaseHit("2026-11", cegueira))), vm.state.value.releases)
        vm.onQuery("blimunda")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, monthsCalls)                                 // the complete load is kept
    }

    /** No month could be loaded: a failure with Retry, not "Nothing in New in Portugal". */
    @Test
    fun noMonthsLoadedIsAFailure() = runTest {
        monthsLoaded = { AllMonths(emptyList(), listOf("2026-11", "2026-10"), null) }
        val vm = vm()
        vm.onQuery("cegueira")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(Section.Failed, vm.state.value.releases)
    }

    /** The first search downloads every month: the section says how far it got. */
    @Test
    fun monthsProgressIsShown() = runTest {
        monthsGate = CompletableDeferred()
        val vm = vm()
        vm.onQuery("cegueira")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(Section.Searching, vm.state.value.releases)
        assertEquals(3 to 7, vm.state.value.monthsLoaded to vm.state.value.monthsTotal)
        monthsGate!!.complete(Unit)
        runCurrent()
        assertEquals(Section.Results(listOf(ReleaseHit("2026-11", cegueira))), vm.state.value.releases)
    }
}
