package com.mcosta.mybooks.ui.social

import com.mcosta.mybooks.social.model.ActivityKind
import com.mcosta.mybooks.social.model.FeedBook
import com.mcosta.mybooks.social.model.FeedEntry
import com.mcosta.mybooks.social.model.Person
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class FeedGroupingTest {
    private val marta = Person("m", "marta", "Marta", null)
    private val rui = Person("r", "rui", "Rui", null)
    private val start = Instant.parse("2026-11-08T12:00:00Z")

    /** Newest first, like the feed: entry [n] is [hoursEach] × n hours older than the first. */
    private fun entries(n: Int, kind: ActivityKind = ActivityKind.WANT_TO_READ, who: Person = marta, firstId: Long = 100, hoursEach: Int = 1) =
        (0 until n).map { i ->
            FeedEntry(
                id = firstId - i, actor = who, kind = kind, rating = null, progress = null,
                createdAt = start - (i * hoursEach).hours, book = FeedBook("b${firstId - i}", null, "Book ${firstId - i}", "A", null),
                review = null, likeCount = 0, likedByMe = false,
            )
        }

    @Test
    fun fiveWantsToReadBecomeOneGroup() {
        val items = groupFeed(entries(5))
        val group = assertIs<FeedItem.Group>(items.single())
        assertEquals(5, group.entries.size)
        assertEquals(ActivityKind.WANT_TO_READ, group.kind)
        assertEquals("g:100", group.key)
    }

    @Test
    fun otherKindsNeverGroup() {
        val items = groupFeed(entries(3, kind = ActivityKind.STARTED))
        assertEquals(listOf("e:100", "e:99", "e:98"), items.map { it.key })
        items.forEach { assertIs<FeedItem.Single>(it) }
    }

    @Test
    fun anotherPersonBreaksTheRun() {
        val list = entries(2) + entries(1, who = rui, firstId = 98).map { it.copy(createdAt = start - 2.hours) } +
            entries(2, firstId = 97).map { it.copy(createdAt = it.createdAt - 3.hours) }
        assertEquals(listOf("g:100", "e:98", "g:97"), groupFeed(list).map { it.key })
    }

    @Test
    fun differentKindsBreakTheRun() {
        val list = entries(1) + entries(1, kind = ActivityKind.WANT_TO_BUY, firstId = 99)
        assertEquals(listOf("e:100", "e:99"), groupFeed(list).map { it.key })
    }

    @Test
    fun moreThan24HoursApartBreaks() {
        val list = entries(2, hoursEach = 25)
        assertEquals(listOf("e:100", "e:99"), groupFeed(list).map { it.key })
        assertEquals(listOf("g:100"), groupFeed(entries(2, hoursEach = 24)).map { it.key })
    }

    @Test
    fun aGroupOfOneIsSingle() {
        assertIs<FeedItem.Single>(groupFeed(entries(1)).single())
    }

    /** Review focus: a run split by a page boundary joins up when the next page loads. */
    @Test
    fun pageBoundaryRejoins() {
        val all = entries(6)
        val page1 = all.take(4)
        val page2 = all.drop(4)
        assertEquals(1, groupFeed(page1 + page2).size)
        assertEquals(6, assertIs<FeedItem.Group>(groupFeed(page1 + page2).single()).entries.size)
    }
}
