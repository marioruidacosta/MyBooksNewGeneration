package com.mcosta.mybooks.ui.social

import com.mcosta.mybooks.social.model.ActivityKind
import com.mcosta.mybooks.social.model.FeedBook
import com.mcosta.mybooks.social.model.FeedEntry
import com.mcosta.mybooks.social.model.Person
import com.mcosta.mybooks.social.model.FriendWithBook
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.ui.formatDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class FeedTextTest {
    private fun entry(kind: ActivityKind, rating: Int? = null, progress: Int? = null) = FeedEntry(
        id = 1, actor = Person("id", "ana", "Ana", null), kind = kind, rating = rating, progress = progress,
        createdAt = Instant.fromEpochSeconds(0), book = FeedBook("s", null, "Dune", "Frank Herbert", null),
        review = null, likeCount = 0, likedByMe = false,
    )

    @Test
    fun actionTextForEachKind() {
        assertEquals("started reading", actionText(entry(ActivityKind.STARTED)))
        assertEquals("finished", actionText(entry(ActivityKind.FINISHED)))
        assertEquals("rated it", actionText(entry(ActivityKind.RATED, rating = 4)))
        assertEquals("wants to read", actionText(entry(ActivityKind.WANT_TO_READ)))
        assertEquals("wants to buy", actionText(entry(ActivityKind.WANT_TO_BUY)))
        assertEquals("reviewed", actionText(entry(ActivityKind.REVIEWED)))
        assertEquals("is 60% through", actionText(entry(ActivityKind.PROGRESS, progress = 60)))
    }

    private val now = Instant.parse("2026-11-02T12:00:00Z")
    private fun ago(d: Duration) = relativeTime(now - d, now)

    @Test
    fun relativeTimeBoundaries() {
        assertEquals("just now", ago(59.seconds))
        assertEquals("1 min ago", ago(61.seconds))
        assertEquals("59 min ago", ago(59.minutes))
        assertEquals("1 h ago", ago(60.minutes))
        assertEquals("23 h ago", ago(23.hours))
        assertEquals("1 d ago", ago(24.hours))
        assertEquals("6 d ago", ago(6.days))
        assertEquals(formatDate((now - 7.days).toEpochMilliseconds()), ago(7.days))
        assertEquals(formatDate((now - 8.days).toEpochMilliseconds()), ago(8.days))
    }

    @Test
    fun futureTimesAreJustNow() {
        assertEquals("just now", relativeTime(now + 30.seconds, now))
    }

    @Test
    fun progressShowsPagesWhenKnown() {
        val e = entry(ActivityKind.PROGRESS, progress = 53).let { it.copy(book = it.book.copy(pages = 344, currentPage = 182)) }
        assertEquals("is on page 182 of 344", actionText(e))
    }

    @Test
    fun progressFallsBackToPercent() {
        val e = entry(ActivityKind.PROGRESS, progress = 53).let { it.copy(book = it.book.copy(pages = 344)) }
        assertEquals("is 53% through", actionText(e))
    }

    @Test
    fun ratedLineAndStars() {
        assertEquals("★★★★☆", starsText(4))
        assertEquals("★★★★★", starsText(9))
        assertEquals(null, starsText(null))
    }

    @Test
    fun groupTextToReadAndToBuy() {
        val five = List(5) { entry(ActivityKind.WANT_TO_READ) }
        assertEquals("added 5 books to To read", groupText(FeedItem.Group(five[0].actor, ActivityKind.WANT_TO_READ, five)))
        assertEquals("added 2 books to To buy", groupText(FeedItem.Group(five[0].actor, ActivityKind.WANT_TO_BUY, five.take(2))))
    }

    @Test
    fun friendWithBookTexts() {
        val ana = Person("id", "ana", "Ana", null)
        assertEquals("read it", friendWithBookText(FriendWithBook(ana, ReadingStatus.READ, 5, 100, "")))
        assertEquals("is reading it · 40%", friendWithBookText(FriendWithBook(ana, ReadingStatus.READING, null, 40, "")))
        assertEquals("is reading it", friendWithBookText(FriendWithBook(ana, ReadingStatus.READING, null, null, "")))
        assertEquals("wants to read it", friendWithBookText(FriendWithBook(ana, ReadingStatus.TO_READ, null, null, "")))
        assertEquals("wants to buy it", friendWithBookText(FriendWithBook(ana, ReadingStatus.WISHLIST, null, null, "")))
    }

    @Test
    fun readingLabelUsesFirstName() {
        val r = com.mcosta.mybooks.social.model.FriendReading(Person("id", "ana", "Ana Silva", null), FeedBook("s", null, "Dune", "", null), 53, Instant.fromEpochSeconds(0))
        assertEquals("Ana · 53%", readingLabel(r))
        assertEquals("Ana", readingLabel(r.copy(progress = null)))
    }

    @Test
    fun aPageAboveTheCountFallsBackToPercent() {
        val e = entry(ActivityKind.PROGRESS, progress = 53).let { it.copy(book = it.book.copy(pages = 344, currentPage = 400)) }
        assertEquals("is 53% through", actionText(e))
    }

    @Test
    fun starsAreDescribedForTalkBack() {
        assertEquals("Rated 4 out of 5", starsDescription(4))
        assertEquals(null, starsDescription(null))
    }
}
