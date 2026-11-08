package com.mcosta.mybooks.social.model

/** Who can see a person's activity (enforced from social part 2). */
enum class Visibility { FRIENDS, PUBLIC }

/** How the signed-in user relates to another person. */
enum class Relationship { NONE, FRIENDS, REQUEST_SENT, REQUEST_RECEIVED }

enum class ReportReason(val label: String) {
    SPAM("Spam"),
    HARASSMENT("Harassment"),
    INAPPROPRIATE("Inappropriate content"),
    IMPERSONATION("Pretending to be someone else"),
    OTHER("Something else"),
}

/** The signed-in user's own profile. */
data class Profile(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val visibility: Visibility,
    /** Whether book changes are shared automatically ("Share my reading activity"). */
    val shareActivity: Boolean = true,
)

/** Another person, as shown in lists, search results and profile pages. */
data class Person(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val visibility: Visibility = Visibility.FRIENDS,
)

data class SearchResult(val person: Person, val relationship: Relationship)

sealed interface SessionState {
    data object Loading : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val userId: String, val email: String?) : SessionState
}

/**
 * Someone BookNerd suggests as a friend: [sharedBooks] books both loved (4–5 stars), the latest of them [topTitle];
 * [mutualFriends] friends in common, up to two of them named in [mutualNames].
 */
data class SuggestedReader(
    val person: Person,
    val sharedBooks: Int,
    val mutualFriends: Int,
    val mutualNames: List<String>,
    val topTitle: String?,
)
