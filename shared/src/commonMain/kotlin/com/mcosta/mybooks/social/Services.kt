package com.mcosta.mybooks.social

import com.mcosta.mybooks.social.model.BookReview
import com.mcosta.mybooks.social.model.BookScore
import com.mcosta.mybooks.social.model.FeedCursor
import com.mcosta.mybooks.social.model.FeedPage
import com.mcosta.mybooks.social.model.FeedScope
import com.mcosta.mybooks.social.model.Person
import com.mcosta.mybooks.social.model.PublicBookState
import com.mcosta.mybooks.social.model.Profile
import com.mcosta.mybooks.social.model.Relationship
import com.mcosta.mybooks.social.model.ReportReason
import com.mcosta.mybooks.social.model.SearchResult
import com.mcosta.mybooks.social.model.SessionState
import com.mcosta.mybooks.social.model.Visibility
import kotlinx.coroutines.flow.StateFlow

/** Failures the social screens explain to the user; every service call throws only this. */
class SocialException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** REJECTED: the server refused the data (e.g. a title over its length limit). */
    enum class Kind { OFFLINE, SECURE_CONNECTION, TIMEOUT, INVALID_CODE, USERNAME_TAKEN, NOT_AVAILABLE, REJECTED, OTHER }
}

/** Email one-time-code sign-in. */
interface AuthService {
    val session: StateFlow<SessionState>
    suspend fun sendCode(email: String)
    suspend fun verifyCode(email: String, code: String)
    suspend fun signOut()
}

interface ProfileService {
    /** The signed-in user's profile, or null if they haven't chosen a username yet. */
    suspend fun myProfile(): Profile?
    suspend fun isUsernameAvailable(username: String): Boolean
    suspend fun createProfile(username: String, displayName: String): Profile
    suspend fun updateProfile(displayName: String, visibility: Visibility): Profile
    suspend fun uploadAvatar(jpeg: ByteArray): Profile
    /** Turns automatic sharing on or off; off also removes everything shared, on the server. */
    suspend fun setShareActivity(enabled: Boolean): Profile
    /** Another person, or null if they don't exist or a block hides them. */
    suspend fun person(userId: String): Person?
    /** Deletes the community account (avatar, profile, friendships); the local library is untouched. */
    suspend fun deleteAccount()
}

interface FriendsService {
    suspend fun friends(): List<Person>
    suspend fun incomingRequests(): List<Person>
    suspend fun outgoingRequests(): List<Person>
    /** At least 2 characters after trimming, else an empty list without a network call. */
    suspend fun search(query: String): List<SearchResult>
    suspend fun relationship(userId: String): Relationship
    /** Sends a request, or accepts theirs if they already asked; returns the new relationship. */
    suspend fun sendRequest(userId: String): Relationship
    suspend fun respond(requesterId: String, accept: Boolean)
    suspend fun cancelRequest(userId: String)
    suspend fun removeFriend(userId: String)
}

interface SafetyService {
    suspend fun block(userId: String)
    suspend fun unblock(userId: String)
    suspend fun blockedPeople(): List<Person>
    /** [activityId]: the feed entry being reported, if any. */
    suspend fun report(userId: String, reason: ReportReason, details: String?, activityId: Long? = null)
}

/** Shared book activity: publishing (by the phone, automatically) and the feed. */
interface ActivityService {
    /** Uploads a book's public state; [before] is its state before the first shared change (null for a new book). */
    suspend fun publish(book: PublicBookState, before: PublicBookState?)
    /** Removes a shared book and its entries. Unknown ids are not an error. */
    suspend fun unpublish(shareId: String)
    suspend fun unpublishAll()
    suspend fun feed(scope: FeedScope, cursor: FeedCursor? = null): FeedPage
    /** One person's entries; empty when the caller may not see them. */
    suspend fun personActivity(userId: String, cursor: FeedCursor? = null): FeedPage
    suspend fun like(activityId: Long)
    suspend fun unlike(activityId: Long)
    suspend fun likers(activityId: Long): List<Person>
    /** The community's scores for the book with [isbn] (readers the caller may see, not the caller). */
    suspend fun bookScore(isbn: String): BookScore
    /** Readers' scores and reviews of the book, written reviews first, newest first; [skip] pages. */
    suspend fun bookReviews(isbn: String, skip: Int = 0): List<BookReview>
}

class SocialServices(
    val auth: AuthService,
    val profiles: ProfileService,
    val friends: FriendsService,
    val safety: SafetyService,
    val activity: ActivityService,
)
