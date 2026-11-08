package com.mcosta.mybooks.data.releases

import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseSearchTest {
    private val cegueira = ReleaseBook("b1", "9789722036504", "Ensaio sobre a Cegueira", listOf("José Saramago"))
    private val maias = ReleaseBook("b2", "9789722039994", "Os Maias", listOf("Eça de Queirós"))
    private fun all(vararg months: Pair<String, List<ReleaseBook>>) =
        AllMonths(months.map { (m, books) -> MonthBooks(m, true, books) }, emptyList(), null)

    @Test
    fun byTitleOrAuthorIgnoringAccentsAndCase() {
        val all = all("2026-11" to listOf(cegueira, maias))
        assertEquals(listOf(ReleaseHit("2026-11", cegueira)), matchReleases(all, "cegueira"))
        assertEquals(listOf(ReleaseHit("2026-11", cegueira)), matchReleases(all, "saramago"))
        assertEquals(listOf(ReleaseHit("2026-11", cegueira)), matchReleases(all, "JOSE"))
        assertEquals(listOf(ReleaseHit("2026-11", maias)), matchReleases(all, "queiros"))
    }

    @Test
    fun byIsbn() {
        val all = all("2026-11" to listOf(cegueira, maias))
        assertEquals(listOf(ReleaseHit("2026-11", cegueira)), matchReleases(all, "978-972-20-3650-4"))
        assertEquals(listOf(ReleaseHit("2026-11", cegueira)), matchReleases(all, "972-20-3650-5"))
    }

    @Test
    fun newestMonthFirstAndAtMost20() {
        fun books(month: String, n: Int) = (1..n).map { ReleaseBook("$month-$it", "978000000000$it", "Romance $it", listOf("Autor")) }
        val all = all("2026-11" to books("nov", 15), "2026-10" to books("oct", 10))
        val hits = matchReleases(all, "romance")
        assertEquals(OUTSIDE_SEARCH_LIMIT, hits.size)
        assertEquals(List(15) { "2026-11" } + List(5) { "2026-10" }, hits.map { it.month })
    }

    @Test
    fun aBookListedInTwoMonthsCountsOnce() {
        val all = all("2026-11" to listOf(cegueira), "2026-10" to listOf(cegueira))
        assertEquals(listOf(ReleaseHit("2026-11", cegueira)), matchReleases(all, "cegueira"))
    }

    @Test
    fun theMonthInProgressIsMarked() {
        val all = AllMonths(listOf(MonthBooks("2026-11", false, listOf(cegueira)), MonthBooks("2026-10", true, listOf(maias))), emptyList(), null)
        assertEquals(listOf(false), matchReleases(all, "cegueira").map { it.complete })
        assertEquals(listOf(true), matchReleases(all, "maias").map { it.complete })
    }

    /** The keys are worked out once per load (thousands of books), then any number of queries. */
    @Test
    fun anIndexAnswersManyQueries() {
        val index = ReleaseIndex(all("2026-11" to listOf(cegueira, maias)))
        assertEquals(listOf("b1"), index.match("saramago").map { it.book.id })
        assertEquals(listOf("b2"), index.match("os maias").map { it.book.id })
        assertEquals(listOf("b1"), index.match("972-20-3650-5").map { it.book.id })
    }
}
