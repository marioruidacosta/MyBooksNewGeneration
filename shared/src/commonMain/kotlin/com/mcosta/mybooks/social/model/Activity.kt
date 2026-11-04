package com.mcosta.mybooks.social.model

import com.mcosta.mybooks.domain.ReadingStatus
import kotlin.time.Instant

enum class ActivityKind { STARTED, FINISHED, WANT_TO_READ, WANT_TO_BUY, RATED, REVIEWED, PROGRESS }

/** The Activity screen's tabs: friends and me, or every public reader. */
enum class FeedScope { FRIENDS, EVERYONE }

/** What the community sees of one book. Also used for the "before" state (status, rating, progress, review). */
data class PublicBookState(
    val shareId: String,
    val isbn: String?,
    val title: String,
    val authors: String,
    val coverUrl: String?,
    val status: ReadingStatus,
    val rating: Int?,
    val progress: Int?,
    val review: String,
)

data class FeedBook(val shareId: String, val isbn: String?, val title: String, val authors: String, val coverUrl: String?)

data class FeedEntry(
    val id: Long,
    val actor: Person,
    val kind: ActivityKind,
    /** The rating or progress when the entry was made (rated / progress entries). */
    val rating: Int?,
    val progress: Int?,
    val createdAt: Instant,
    val book: FeedBook,
    /** The book's current review, only on reviewed entries. */
    val review: String?,
    val likeCount: Int,
    val likedByMe: Boolean,
)

/** Where the next page starts: the last entry seen. */
data class FeedCursor(val createdAt: Instant, val id: Long)

/** [next] is null when there are no more entries. */
data class FeedPage(val entries: List<FeedEntry>, val next: FeedCursor?)

const val FEED_PAGE_SIZE = 20

/** Reviews per page of [ActivityService.bookReviews] (the server function's default). */
const val BOOK_REVIEWS_PAGE = 10

/** The community's scores for a book: [perScore] is how many gave 5, 4, 3, 2 and 1 stars, in that order. */
data class BookScore(val ratings: Int, val average: Double?, val perScore: List<Int>, val reviews: Int) {
    companion object {
        val NONE = BookScore(0, null, List(5) { 0 }, 0)
    }
}

/** A reader's score and review of a book. */
data class BookReview(val person: Person, val rating: Int?, val review: String, val updatedAt: Instant)

/** A book community readers loved: [readers] of them who also loved the reader's favourite [because] (an ISBN). */
data class CommunitySuggestion(
    val isbn: String,
    val title: String,
    val authors: String,
    val coverUrl: String?,
    val readers: Int,
    val because: String,
)
