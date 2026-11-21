package com.mcosta.mybooks.data.news

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class NewsListsTest {
    private fun item(id: String, lang: String, day: String) =
        NewsItem(id, id, "", "https://x.test/$id", "s", lang, Instant.parse("${day}T12:00:00Z").toEpochMilliseconds())

    @Test
    fun filtersByLanguage() {
        val items = listOf(item("a", "pt", "2026-11-17"), item("b", "en", "2026-11-17"), item("c", "pt", "2026-11-16"))
        assertEquals(listOf("a", "b", "c"), items.inLanguage(NewsLanguage.ALL).map { it.id })
        assertEquals(listOf("a", "c"), items.inLanguage(NewsLanguage.PT).map { it.id })
        assertEquals(listOf("b"), items.inLanguage(NewsLanguage.EN).map { it.id })
    }

    @Test
    fun groupsByDayWithTodayAndYesterday() {
        val items = listOf(item("a", "pt", "2026-11-17"), item("b", "en", "2026-11-17"), item("c", "pt", "2026-11-16"), item("d", "pt", "2026-11-03"))
        val days = items.byDay(LocalDate(2026, 11, 17), TimeZone.UTC)
        assertEquals(listOf("Today", "Yesterday", "3 Nov 2026"), days.map { it.label })
        assertEquals(listOf(listOf("a", "b"), listOf("c"), listOf("d")), days.map { d -> d.items.map { it.id } })
    }

    @Test
    fun calendarStartsAtThisMonthAndWrapsRoundTheYear() {
        val events = listOf(1, 3, 10, 11, 12).map { BookEvent("e$it", "p", it, "fair", "en", "https://e.test") }
        assertEquals(listOf("e11", "e12", "e1", "e3", "e10"), events.fromMonth(11).map { it.name })
        assertEquals("Usually in October, Frankfurt", BookEvent("n", "Frankfurt", 10, "fair", "en", "https://e.test").usually())
    }
}
