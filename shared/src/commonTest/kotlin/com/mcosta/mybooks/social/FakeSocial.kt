package com.mcosta.mybooks.social

import com.mcosta.mybooks.social.model.ActivityKind
import com.mcosta.mybooks.social.model.BOOK_REVIEWS_PAGE
import com.mcosta.mybooks.social.model.BookReview
import com.mcosta.mybooks.social.model.BookScore
import com.mcosta.mybooks.social.model.CommunitySuggestion
import com.mcosta.mybooks.social.model.FriendReading
import com.mcosta.mybooks.social.model.FriendWithBook
import com.mcosta.mybooks.social.model.FEED_PAGE_SIZE
import com.mcosta.mybooks.social.model.FeedBook
import com.mcosta.mybooks.social.model.FeedCursor
import com.mcosta.mybooks.social.model.FeedEntry
import com.mcosta.mybooks.social.model.FeedPage
import com.mcosta.mybooks.social.model.FeedScope
import com.mcosta.mybooks.social.model.Person
import com.mcosta.mybooks.social.model.Profile
import com.mcosta.mybooks.social.model.PublicBookState
import com.mcosta.mybooks.social.model.Relationship
import com.mcosta.mybooks.social.model.ReportReason
import com.mcosta.mybooks.social.model.SearchResult
import com.mcosta.mybooks.social.model.SessionState
import com.mcosta.mybooks.social.model.Visibility
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/**
 * In-memory social network following the same rules as the SQL migrations: mutual requests,
 * crossed requests become friends, blocks remove friendships and hide people both ways, and
 * activity is visible to the actor, their friends, and everyone when the actor is public.
 */
class FakeSocial {
    data class Report(val reporter: String, val reported: String, val reason: ReportReason, val details: String?, val activityId: Long? = null)

    /** A seeded feed entry (the fake doesn't derive entries from published states). */
    private data class Entry(val id: Long, val actor: String, val kind: ActivityKind, val title: String, val isbn: String?)

    private val entries = mutableListOf<Entry>()
    private val likes = mutableSetOf<Pair<Long, String>>()      // entry id -> liker
    val published = mutableListOf<Pair<PublicBookState, PublicBookState?>>()
    val unpublished = mutableListOf<String>()
    var unpublishedAll = 0
    /** Thrown by publish/unpublish when set (e.g. to simulate a failed upload). */
    var failPublish: SocialException? = null
    /** Shared ids that belong to another account: publishing them fails with NOT_AVAILABLE. */
    val takenShareIds = mutableSetOf<String>()
    /** Shared ids whose data the server rejects (e.g. too long). */
    val rejectedShareIds = mutableSetOf<String>()
    var publishAttempts = 0
    var personActivityCalls = 0
    /** Readers' scores and reviews per ISBN (already only those the signed-in user may see). */
    val bookReviewsByIsbn = mutableMapOf<String, List<BookReview>>()
    var bookReviewCalls = 0
    /** Community suggestions to answer with, and the favourites asked about. */
    var communitySuggestions = emptyList<CommunitySuggestion>()
    var likedAskedAbout = emptyList<String>()
    /** What friendsReading() returns; [failReading] makes it fail. */
    var reading = emptyList<FriendReading>()
    var failReading = false
    var readingCalls = 0
    /** friendsWithBook() answers by ISBN; [failWithBook] makes it fail. */
    var withBook = mapOf<String, List<FriendWithBook>>()
    var failWithBook = false
    /** Runs inside publish, before it succeeds (e.g. to change the book mid-upload). */
    var onPublish: suspend () -> Unit = {}
    /** Runs inside like and unlike before they take effect (e.g. to hold the call in flight). */
    var onLike: suspend () -> Unit = {}

    private val profiles = linkedMapOf<String, Profile>()
    private val emails = mutableMapOf<String, String>()          // email -> user id
    private val pending = mutableSetOf<Pair<String, String>>()   // requester -> addressee
    private val friendsSet = mutableSetOf<Set<String>>()
    private val blocks = mutableSetOf<Pair<String, String>>()    // blocker -> blocked
    val reports = mutableListOf<Report>()
    val uploadedAvatars = mutableListOf<ByteArray>()
    var offline = false
    var validCode = "123456"
    var searchCalls = 0
    var availabilityChecks = 0

    private val sessionFlow = MutableStateFlow<SessionState>(SessionState.SignedOut)

    /** Adds a user with a profile; returns their id. */
    fun addUser(username: String, displayName: String = username.replaceFirstChar { it.uppercase() }): String {
        val id = "id-$username"
        profiles[id] = Profile(id, username, displayName, null, Visibility.FRIENDS)
        return id
    }

    /** Signs in as [userId] directly (with or without a profile). */
    fun signInAs(userId: String) {
        sessionFlow.value = SessionState.SignedIn(userId, null)
    }

    private fun me(): String = (sessionFlow.value as? SessionState.SignedIn)?.userId
        ?: throw SocialException(SocialException.Kind.OTHER, "not signed in")

    private fun check() {
        if (offline) throw SocialException(SocialException.Kind.OFFLINE, "offline")
    }

    private fun blockedBetween(a: String, b: String) = (a to b) in blocks || (b to a) in blocks

    private fun person(p: Profile) = Person(p.id, p.username, p.displayName, p.avatarUrl, p.visibility)

    fun setVisibility(userId: String, visibility: Visibility) {
        profiles[userId] = profiles.getValue(userId).copy(visibility = visibility)
    }

    fun makeFriends(a: String, b: String) {
        friendsSet += setOf(a, b)
    }

    /** Adds a feed entry by [actorId]; entries added later are newer. Returns its id. */
    fun addEntry(actorId: String, kind: ActivityKind, title: String = "Book", isbn: String? = null): Long {
        val id = entries.size + 1L
        entries += Entry(id, actorId, kind, title, isbn)
        return id
    }

    private fun canSeeActivityOf(me: String, actor: String): Boolean = actor == me ||
        (!blockedBetween(me, actor) && (profiles[actor]?.visibility == Visibility.PUBLIC || setOf(me, actor) in friendsSet))

    private fun toFeedEntry(e: Entry, me: String) = FeedEntry(
        id = e.id,
        actor = person(profiles.getValue(e.actor)),
        kind = e.kind,
        rating = if (e.kind == ActivityKind.RATED) 4 else null,
        progress = if (e.kind == ActivityKind.PROGRESS) 50 else null,
        createdAt = Instant.fromEpochSeconds(1_800_000_000L + e.id * 60),
        book = FeedBook("share-${e.id}", e.isbn, e.title, "Author", null),
        review = if (e.kind == ActivityKind.REVIEWED) "A review" else null,
        likeCount = likes.count { it.first == e.id },
        likedByMe = (e.id to me) in likes,
    )

    private fun page(visible: List<Entry>, cursor: FeedCursor?, me: String): FeedPage {
        val newestFirst = visible.sortedByDescending { it.id }.filter { cursor == null || it.id < cursor.id }
        val items = newestFirst.take(FEED_PAGE_SIZE).map { toFeedEntry(it, me) }
        val next = items.takeIf { it.size == FEED_PAGE_SIZE }?.last()?.let { FeedCursor(it.createdAt, it.id) }
        return FeedPage(items, next)
    }

    private fun relationshipOf(me: String, other: String) = when {
        setOf(me, other) in friendsSet -> Relationship.FRIENDS
        (me to other) in pending -> Relationship.REQUEST_SENT
        (other to me) in pending -> Relationship.REQUEST_RECEIVED
        else -> Relationship.NONE
    }

    fun relationshipBetween(a: String, b: String) = relationshipOf(a, b)

    val auth = object : AuthService {
        override val session: StateFlow<SessionState> = sessionFlow
        override suspend fun sendCode(email: String) = check()
        override suspend fun verifyCode(email: String, code: String) {
            check()
            if (code != validCode) throw SocialException(SocialException.Kind.INVALID_CODE, "bad code")
            val id = emails.getOrPut(email) { "id-new-${emails.size}" }
            sessionFlow.value = SessionState.SignedIn(id, email)
        }
        override suspend fun signOut() {
            sessionFlow.value = SessionState.SignedOut
        }
    }

    val profileService = object : ProfileService {
        override suspend fun myProfile(): Profile? { check(); return profiles[me()] }
        override suspend fun isUsernameAvailable(username: String): Boolean {
            check(); availabilityChecks++; return profiles.values.none { it.username == username }
        }
        override suspend fun createProfile(username: String, displayName: String): Profile {
            check()
            if (profiles.values.any { it.username == username }) throw SocialException(SocialException.Kind.USERNAME_TAKEN, "taken")
            return Profile(me(), username, displayName, null, Visibility.FRIENDS).also { profiles[it.id] = it }
        }
        override suspend fun updateProfile(displayName: String, visibility: Visibility): Profile {
            check(); return profiles.getValue(me()).copy(displayName = displayName, visibility = visibility).also { profiles[it.id] = it }
        }
        override suspend fun uploadAvatar(jpeg: ByteArray): Profile {
            check(); uploadedAvatars += jpeg
            return profiles.getValue(me()).copy(avatarUrl = "https://avatars/${me()}.jpg").also { profiles[it.id] = it }
        }
        override suspend fun setShareActivity(enabled: Boolean): Profile {
            check()
            val me = me()
            if (!enabled) entries.removeAll { it.actor == me }
            return profiles.getValue(me).copy(shareActivity = enabled).also { profiles[it.id] = it }
        }
        override suspend fun person(userId: String): Person? {
            check(); return profiles[userId]?.takeUnless { blockedBetween(me(), userId) }?.let(::person)
        }
        override suspend fun deleteAccount() {
            check()
            val id = me()
            profiles.remove(id)
            pending.removeAll { id in it.toList() }
            friendsSet.removeAll { id in it }
            blocks.removeAll { it.first == id }
            sessionFlow.value = SessionState.SignedOut
        }
    }

    val friendsService = object : FriendsService {
        override suspend fun friends(): List<Person> {
            check(); val me = me(); return friendsSet.filter { me in it }.mapNotNull { pair -> profiles[(pair - me).single()]?.let(::person) }
        }
        override suspend fun incomingRequests(): List<Person> {
            check(); val me = me(); return pending.filter { it.second == me }.mapNotNull { profiles[it.first]?.let(::person) }
        }
        override suspend fun outgoingRequests(): List<Person> {
            check(); val me = me(); return pending.filter { it.first == me }.mapNotNull { profiles[it.second]?.let(::person) }
        }
        override suspend fun search(query: String): List<SearchResult> {
            check()
            val q = query.trim()
            if (q.length < 2) return emptyList()
            searchCalls++
            val me = me()
            return profiles.values
                .filter { it.id != me && !blockedBetween(me, it.id) }
                .filter { it.username.contains(q, ignoreCase = true) || it.displayName.contains(q, ignoreCase = true) }
                .map { SearchResult(person(it), relationshipOf(me, it.id)) }
        }
        override suspend fun relationship(userId: String): Relationship { check(); return relationshipOf(me(), userId) }
        override suspend fun sendRequest(userId: String): Relationship {
            check()
            val me = me()
            if (userId == me || userId !in profiles || blockedBetween(me, userId)) {
                throw SocialException(SocialException.Kind.NOT_AVAILABLE, "not available")
            }
            when (relationshipOf(me, userId)) {
                Relationship.REQUEST_RECEIVED -> { pending -= (userId to me); friendsSet += setOf(me, userId) }
                Relationship.NONE -> pending += (me to userId)
                else -> Unit
            }
            return relationshipOf(me, userId)
        }
        override suspend fun respond(requesterId: String, accept: Boolean) {
            check()
            val me = me()
            if (!pending.remove(requesterId to me)) throw SocialException(SocialException.Kind.NOT_AVAILABLE, "no request")
            if (accept) friendsSet += setOf(me, requesterId)
        }
        override suspend fun cancelRequest(userId: String) { check(); pending -= (me() to userId) }
        override suspend fun removeFriend(userId: String) { check(); friendsSet -= setOf(me(), userId) }
    }

    val safetyService = object : SafetyService {
        override suspend fun block(userId: String) {
            check()
            val me = me()
            blocks += (me to userId)
            pending.removeAll { it == (me to userId) || it == (userId to me) }
            friendsSet -= setOf(me, userId)
        }
        override suspend fun unblock(userId: String) { check(); blocks -= (me() to userId) }
        override suspend fun blockedPeople(): List<Person> {
            check(); val me = me(); return blocks.filter { it.first == me }.mapNotNull { profiles[it.second]?.let(::person) }
        }
        override suspend fun report(userId: String, reason: ReportReason, details: String?, activityId: Long?) {
            check(); reports += Report(me(), userId, reason, details, activityId)
        }
    }

    val activityService = object : ActivityService {
        override suspend fun bookScore(isbn: String): BookScore {
            check()
            val all = bookReviewsByIsbn[isbn].orEmpty()
            val rated = all.mapNotNull { it.rating }
            return BookScore(rated.size, rated.takeIf { it.isNotEmpty() }?.average(), (5 downTo 1).map { s -> rated.count { it == s } }, all.count { it.review.isNotEmpty() })
        }
        override suspend fun friendsReading(): List<FriendReading> {
            check()
            readingCalls++
            if (failReading) throw SocialException(SocialException.Kind.OFFLINE, "offline")
            return reading
        }
        override suspend fun friendsWithBook(isbn: String): List<FriendWithBook> {
            check()
            if (failWithBook) throw SocialException(SocialException.Kind.OFFLINE, "offline")
            return withBook[isbn].orEmpty()
        }
        override suspend fun communitySuggestions(likedIsbns: List<String>): List<CommunitySuggestion> {
            check()
            likedAskedAbout = likedIsbns
            return communitySuggestions
        }
        override suspend fun bookReviews(isbn: String, skip: Int): List<BookReview> {
            check()
            bookReviewCalls++
            return bookReviewsByIsbn[isbn].orEmpty().drop(skip).take(BOOK_REVIEWS_PAGE)
        }
        override suspend fun publish(book: PublicBookState, before: PublicBookState?) {
            check()
            publishAttempts++
            failPublish?.let { throw it }
            if (book.shareId in takenShareIds) throw SocialException(SocialException.Kind.NOT_AVAILABLE, "not available")
            if (book.shareId in rejectedShareIds) throw SocialException(SocialException.Kind.REJECTED, "invalid book")
            onPublish()
            published += book to before
        }
        override suspend fun unpublish(shareId: String) {
            check(); failPublish?.let { throw it }; unpublished += shareId
        }
        override suspend fun unpublishAll() {
            check(); unpublishedAll++
        }
        override suspend fun feed(scope: FeedScope, cursor: FeedCursor?): FeedPage {
            check()
            val me = me()
            val visible = entries.filter { e ->
                !blockedBetween(me, e.actor) && when (scope) {
                    FeedScope.FRIENDS -> e.actor == me || setOf(me, e.actor) in friendsSet
                    FeedScope.EVERYONE -> profiles[e.actor]?.visibility == Visibility.PUBLIC
                }
            }
            return page(visible, cursor, me)
        }
        override suspend fun personActivity(userId: String, cursor: FeedCursor?): FeedPage {
            check()
            personActivityCalls++
            val me = me()
            return page(if (canSeeActivityOf(me, userId)) entries.filter { it.actor == userId } else emptyList(), cursor, me)
        }
        override suspend fun like(activityId: Long) {
            check()
            onLike()
            val me = me()
            val e = entries.firstOrNull { it.id == activityId }
            if (e == null || e.actor == me || !canSeeActivityOf(me, e.actor)) {
                throw SocialException(SocialException.Kind.NOT_AVAILABLE, "not available")
            }
            likes += activityId to me
        }
        override suspend fun unlike(activityId: Long) {
            check(); onLike(); likes -= activityId to me()
        }
        override suspend fun likers(activityId: Long): List<Person> {
            check()
            val me = me()
            val e = entries.firstOrNull { it.id == activityId } ?: return emptyList()
            if (!canSeeActivityOf(me, e.actor)) return emptyList()
            return likes.filter { it.first == activityId && !blockedBetween(me, it.second) }
                .mapNotNull { profiles[it.second]?.let(::person) }
        }
    }

    val services = SocialServices(auth, profileService, friendsService, safetyService, activityService)
}
