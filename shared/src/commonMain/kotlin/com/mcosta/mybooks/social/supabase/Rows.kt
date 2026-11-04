package com.mcosta.mybooks.social.supabase

import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.social.model.ActivityKind
import com.mcosta.mybooks.social.model.FeedBook
import com.mcosta.mybooks.social.model.FeedEntry
import com.mcosta.mybooks.social.model.Person
import com.mcosta.mybooks.social.model.Profile
import com.mcosta.mybooks.social.model.PublicBookState
import com.mcosta.mybooks.social.model.Relationship
import com.mcosta.mybooks.social.model.Visibility
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant
import com.mcosta.mybooks.social.model.BookReview
import com.mcosta.mybooks.social.model.BookScore
import com.mcosta.mybooks.social.model.CommunitySuggestion

/** A row of the feed / person_activity functions. */
@Serializable
internal data class FeedRow(
    val id: Long,
    val kind: String,
    val rating: Int? = null,
    val progress: Int? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("actor_id") val actorId: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_path") val avatarPath: String? = null,
    @SerialName("book_id") val bookId: String,
    val isbn: String? = null,
    val title: String,
    val authors: String = "",
    @SerialName("cover_url") val coverUrl: String? = null,
    val review: String? = null,
    @SerialName("like_count") val likeCount: Int = 0,
    @SerialName("liked_by_me") val likedByMe: Boolean = false,
)

internal fun FeedRow.toEntry(avatarUrl: (String?) -> String?) = FeedEntry(
    id = id,
    actor = Person(actorId, username, displayName, avatarUrl(avatarPath)),
    kind = ActivityKind.valueOf(kind.uppercase()),
    rating = rating,
    progress = progress,
    createdAt = Instant.parse(createdAt),
    book = FeedBook(bookId, isbn, title, authors, coverUrl),
    review = review,
    likeCount = likeCount,
    likedByMe = likedByMe,
)

internal fun ReadingStatus.dbValue() = when (this) {
    ReadingStatus.READING -> "reading"
    ReadingStatus.READ -> "read"
    ReadingStatus.TO_READ -> "to_read"
    ReadingStatus.WISHLIST -> "to_buy"
}

/** publish_book's `book` argument. */
internal fun PublicBookState.toBookJson(): JsonObject = buildJsonObject {
    put("id", shareId)
    put("isbn", isbn)
    put("title", title)
    put("authors", authors)
    put("cover_url", coverUrl)
    put("status", status.dbValue())
    put("rating", rating)
    put("progress", progress)
    put("review", review)
}

/** publish_book's `before` argument: only the fields that decide which entries are created. */
internal fun PublicBookState.toBeforeJson(): JsonObject = buildJsonObject {
    put("status", status.dbValue())
    put("rating", rating)
    put("progress", progress)
    put("review", review)
}

/** Rows as PostgREST returns them (column names from supabase/migrations). */
@Serializable
internal data class ProfileRow(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_path") val avatarPath: String? = null,
    val visibility: String = "friends",
    @SerialName("share_activity") val shareActivity: Boolean = true,
)

@Serializable
internal data class NewProfileRow(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
)

@Serializable
internal data class FriendshipRow(val requester: String, val addressee: String, val status: String)

@Serializable
internal data class NewReportRow(
    val reporter: String,
    val reported: String,
    val reason: String,
    val details: String?,
    val activity: Long? = null,
)

internal fun visibilityOf(value: String) = if (value == "public") Visibility.PUBLIC else Visibility.FRIENDS

internal fun Visibility.dbValue() = if (this == Visibility.PUBLIC) "public" else "friends"

internal fun ProfileRow.toProfile(avatarUrl: (String?) -> String?) =
    Profile(id, username, displayName, avatarUrl(avatarPath), visibilityOf(visibility), shareActivity)

internal fun ProfileRow.toPerson(avatarUrl: (String?) -> String?) =
    Person(id, username, displayName, avatarUrl(avatarPath), visibilityOf(visibility))

/** How [me] relates to [other], from the friendship rows visible to me. */
internal fun relationshipFrom(rows: List<FriendshipRow>, me: String, other: String): Relationship {
    val row = rows.firstOrNull { setOf(it.requester, it.addressee) == setOf(me, other) } ?: return Relationship.NONE
    return when {
        row.status == "accepted" -> Relationship.FRIENDS
        row.requester == me -> Relationship.REQUEST_SENT
        else -> Relationship.REQUEST_RECEIVED
    }
}

/** The book_score function's row. */
@Serializable
internal data class BookScoreRow(
    val ratings: Int = 0,
    val average: Double? = null,
    val five: Int = 0,
    val four: Int = 0,
    val three: Int = 0,
    val two: Int = 0,
    val one: Int = 0,
    val reviews: Int = 0,
) {
    fun toScore() = BookScore(ratings, average, listOf(five, four, three, two, one), reviews)
}

/** A row of the book_reviews function. */
@Serializable
internal data class BookReviewRow(
    @SerialName("person_id") val personId: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_path") val avatarPath: String? = null,
    val rating: Int? = null,
    val review: String = "",
    @SerialName("updated_at") val updatedAt: String,
) {
    fun toReview(avatarUrl: (String?) -> String?) =
        BookReview(Person(personId, username, displayName, avatarUrl(avatarPath)), rating, review, Instant.parse(updatedAt))
}

/** A row of the community_suggestions function. */
@Serializable
internal data class CommunitySuggestionRow(
    val isbn: String,
    val title: String,
    val authors: String = "",
    @SerialName("cover_url") val coverUrl: String? = null,
    val readers: Int = 0,
    val because: String = "",
) {
    fun toSuggestion() = CommunitySuggestion(isbn, title, authors, coverUrl, readers, because)
}
