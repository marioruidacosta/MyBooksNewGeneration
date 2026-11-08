package com.mcosta.mybooks.ui.social

import com.mcosta.mybooks.social.model.ActivityKind
import com.mcosta.mybooks.social.model.FeedEntry
import com.mcosta.mybooks.social.model.FriendWithBook
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.ui.formatDate
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * What the person did, after their name: "started reading", "rated it" (the stars go on their own line, see
 * [starsText]), "is on page 182 of 344" or, without pages, "is 60% through".
 */
fun actionText(entry: FeedEntry): String = when (entry.kind) {
    ActivityKind.STARTED -> "started reading"
    ActivityKind.FINISHED -> "finished"
    ActivityKind.WANT_TO_READ -> "wants to read"
    ActivityKind.WANT_TO_BUY -> "wants to buy"
    ActivityKind.REVIEWED -> "reviewed"
    ActivityKind.RATED -> "rated it"
    ActivityKind.PROGRESS -> {
        val page = entry.book.currentPage
        val pages = entry.book.pages
        when {
            page != null && pages != null -> "is on page $page of $pages"
            entry.progress != null -> "is ${entry.progress}% through"
            else -> "is reading"
        }
    }
}

/** A score as stars: 4 → "★★★★☆"; null for none. */
fun starsText(rating: Int?): String? = rating?.coerceIn(0, 5)?.let { "★".repeat(it) + "☆".repeat(5 - it) }

/** A grouped card's line, after the name: "added 5 books to To read". */
fun groupText(group: FeedItem.Group): String =
    "added ${group.entries.size} books to " + if (group.kind == ActivityKind.WANT_TO_BUY) "To buy" else "To read"

/** A friend's line on a book's page, after their name: "read it", "is reading it · 40%", "wants to read it". */
fun friendWithBookText(friend: FriendWithBook): String = when (friend.status) {
    ReadingStatus.READ -> "read it"
    ReadingStatus.READING -> friend.progress?.let { "is reading it · $it%" } ?: "is reading it"
    ReadingStatus.TO_READ -> "wants to read it"
    ReadingStatus.WISHLIST -> "wants to buy it"
}

/** "just now", "5 min ago", "2 h ago", "3 d ago", then the date. */
fun relativeTime(then: Instant, now: Instant = Clock.System.now()): String {
    val elapsed = now - then
    return when {
        elapsed < 60.seconds -> "just now"
        elapsed < 60.minutes -> "${elapsed.inWholeMinutes} min ago"
        elapsed < 24.hours -> "${elapsed.inWholeHours} h ago"
        elapsed < 7.days -> "${elapsed.inWholeDays} d ago"
        else -> formatDate(then.toEpochMilliseconds())
    }
}
