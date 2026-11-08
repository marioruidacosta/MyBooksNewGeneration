package com.mcosta.mybooks.social.supabase

import com.mcosta.mybooks.social.ActivityService
import com.mcosta.mybooks.social.AuthService
import com.mcosta.mybooks.social.FriendsService
import com.mcosta.mybooks.social.ProfileService
import com.mcosta.mybooks.social.SafetyService
import com.mcosta.mybooks.social.SocialConfig
import com.mcosta.mybooks.social.SocialException
import com.mcosta.mybooks.social.SocialServices
import com.mcosta.mybooks.social.model.FEED_PAGE_SIZE
import com.mcosta.mybooks.social.model.BookReview
import com.mcosta.mybooks.social.model.BookScore
import com.mcosta.mybooks.social.model.CommunitySuggestion
import com.mcosta.mybooks.social.model.FriendReading
import com.mcosta.mybooks.social.model.SuggestedReader
import com.mcosta.mybooks.social.model.FriendWithBook
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
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.OtpType
import com.russhwolf.settings.Settings
import io.github.jan.supabase.auth.SettingsCodeVerifierCache
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.resumable.MemoryResumableCache
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.storage
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock

/** The app's social services on a real Supabase project. */
/**
 * [settings]: where the sign-in session is kept; null uses the library default (NSUserDefaults on
 * iOS). Android passes its SharedPreferences: the default there needs an androidx-startup hook.
 */
fun createSocialServices(config: SocialConfig, engine: HttpClientEngine, settings: Settings? = null): SocialServices =
    SupabaseSocial(supabaseClient(config, engine, persistSession = true, settings)).services

/**
 * How long a Supabase call may take. Sending the sign-in email happens inside the request, and
 * Supabase's built-in mailer can exceed the library's 10 s default.
 */
internal val SUPABASE_REQUEST_TIMEOUT = 30.seconds

/** Request, socket or connect timeouts, also when supabase-kt wraps them (keeping only the message). */
private fun isTimeout(e: Throwable) = generateSequence(e) { it.cause }.take(10).any {
    it is HttpRequestTimeoutException || it is SocketTimeoutException || it is ConnectTimeoutException ||
        it.message.orEmpty().contains("timeout has expired", ignoreCase = true)
}

internal fun supabaseClient(
    config: SocialConfig,
    engine: HttpClientEngine,
    persistSession: Boolean,
    settings: Settings? = null,
): SupabaseClient =
    createSupabaseClient(supabaseUrl = config.url.trim(), supabaseKey = config.anonKey.trim()) {
        httpEngine = engine
        requestTimeout = SUPABASE_REQUEST_TIMEOUT
        install(Auth) {
            if (settings != null) {
                sessionManager = SettingsSessionManager(settings)
                codeVerifierCache = SettingsCodeVerifierCache(settings)
            }
            if (!persistSession) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                alwaysAutoRefresh = false
                disablePlatformLifecycle()
            }
        }
        install(Postgrest)
        // Only plain uploads are used; the default resumable-upload cache would need platform Settings.
        install(Storage) { resumable { cache = MemoryResumableCache() } }
        install(Realtime)
    }

/** Maps any failure to the [SocialException] kinds the screens explain. */
internal fun mapSocialError(e: Throwable): SocialException = when {
    e is SocialException -> e
    isTlsFailure(e) -> SocialException(SocialException.Kind.SECURE_CONNECTION, "secure connection failed", e)
    isTimeout(e) -> SocialException(SocialException.Kind.TIMEOUT, "timed out", e)
    e is HttpRequestException || e is IOException -> SocialException(SocialException.Kind.OFFLINE, "offline", e)
    e is AuthRestException && (e.errorCode?.value == "otp_expired" || e.message.orEmpty().contains("expired", ignoreCase = true) ||
        e.message.orEmpty().contains("invalid", ignoreCase = true)) ->
        SocialException(SocialException.Kind.INVALID_CODE, "invalid code", e)
    e is PostgrestRestException && e.code == "23505" -> SocialException(SocialException.Kind.USERNAME_TAKEN, "username taken", e)
    e is PostgrestRestException && e.code == "P0002" -> SocialException(SocialException.Kind.NOT_AVAILABLE, "not available", e)
    e is PostgrestRestException && e.code == "22023" -> SocialException(SocialException.Kind.REJECTED, "rejected", e)
    else -> SocialException(SocialException.Kind.OTHER, e.message ?: "error", e)
}

internal class SupabaseSocial(
    private val client: SupabaseClient,
    private val currentUserId: () -> String? = { client.auth.currentUserOrNull()?.id },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val avatarBase = client.supabaseUrl.let { if (it.startsWith("http")) it else "https://$it" }.trimEnd('/')

    private fun avatarUrl(path: String?): String? = path?.let { "$avatarBase/storage/v1/object/public/avatars/$it" }

    private fun me(): String = currentUserId() ?: throw SocialException(SocialException.Kind.OTHER, "not signed in")

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logSocialError(e)
        throw mapSocialError(e)
    }

    private suspend fun profilesByIds(ids: List<String>): List<Person> {
        if (ids.isEmpty()) return emptyList()
        return client.from("profiles").select { filter { isIn("id", ids) } }
            .decodeList<ProfileRow>().map { it.toPerson(::avatarUrl) }.sortedBy { it.displayName.lowercase() }
    }

    private suspend fun myFriendships(): List<FriendshipRow> = client.from("friendships").select().decodeList()

    private val auth = object : AuthService {
        override val session: StateFlow<SessionState> = client.auth.sessionStatus.map { status ->
            when (status) {
                is SessionStatus.Authenticated -> status.session.user?.let { SessionState.SignedIn(it.id, it.email) } ?: SessionState.SignedOut
                is SessionStatus.Initializing -> SessionState.Loading
                // Expired token and no network: the session is kept and refreshed later, so stay signed in.
                is SessionStatus.RefreshFailure ->
                    client.auth.currentSessionOrNull()?.user?.let { SessionState.SignedIn(it.id, it.email) } ?: SessionState.SignedOut
                else -> SessionState.SignedOut
            }
        }.stateIn(scope, SharingStarted.Eagerly, SessionState.Loading)

        override suspend fun sendCode(email: String) = call { client.auth.signInWith(OTP) { this.email = email.trim() } }

        override suspend fun verifyCode(email: String, code: String) = call {
            client.auth.verifyEmailOtp(type = OtpType.Email.EMAIL, email = email.trim(), token = code.trim())
            Unit
        }

        override suspend fun signOut() = call { client.auth.signOut() }
    }

    private val profiles = object : ProfileService {
        override suspend fun myProfile(): Profile? = call {
            client.from("profiles").select { filter { eq("id", me()) } }.decodeSingleOrNull<ProfileRow>()?.toProfile(::avatarUrl)
        }

        override suspend fun isUsernameAvailable(username: String): Boolean = call {
            client.from("profiles").select { filter { eq("username", username) } }.decodeList<ProfileRow>().isEmpty()
        }

        override suspend fun createProfile(username: String, displayName: String): Profile = call {
            client.from("profiles").insert(NewProfileRow(me(), username, displayName.trim())) { select() }
                .decodeSingle<ProfileRow>().toProfile(::avatarUrl)
        }

        override suspend fun updateProfile(displayName: String, visibility: Visibility): Profile = call {
            client.from("profiles").update({
                set("display_name", displayName.trim())
                set("visibility", visibility.dbValue())
            }) {
                select()
                filter { eq("id", me()) }
            }.decodeSingle<ProfileRow>().toProfile(::avatarUrl)
        }

        override suspend fun uploadAvatar(jpeg: ByteArray): Profile = call {
            val me = me()
            val old = client.from("profiles").select { filter { eq("id", me) } }.decodeSingle<ProfileRow>().avatarPath
            // A new file name each time, so image caches never show the old photo.
            val path = "$me/avatar-${Clock.System.now().toEpochMilliseconds()}.jpg"
            client.storage.from("avatars").upload(path, jpeg) { upsert = false }
            val updated = client.from("profiles").update({ set("avatar_path", path) }) {
                select()
                filter { eq("id", me) }
            }.decodeSingle<ProfileRow>()
            if (old != null) runCatching { client.storage.from("avatars").delete(old) }
            updated.toProfile(::avatarUrl)
        }

        override suspend fun setShareActivity(enabled: Boolean): Profile = call {
            client.postgrest.rpc("set_share_activity", buildJsonObject { put("enabled", enabled) })
            client.from("profiles").select { filter { eq("id", me()) } }.decodeSingle<ProfileRow>().toProfile(::avatarUrl)
        }

        override suspend fun person(userId: String): Person? = call {
            client.from("profiles").select { filter { eq("id", userId) } }.decodeSingleOrNull<ProfileRow>()?.toPerson(::avatarUrl)
        }

        override suspend fun deleteAccount() = call {
            val avatar = client.from("profiles").select { filter { eq("id", me()) } }.decodeSingleOrNull<ProfileRow>()?.avatarPath
            if (avatar != null) runCatching { client.storage.from("avatars").delete(avatar) }
            client.postgrest.rpc("delete_my_account")
            // The account is gone server-side; only the local session is left to clear.
            client.auth.clearSession()
        }
    }

    private val friends = object : FriendsService {
        override suspend fun friends(): List<Person> = call {
            val me = me()
            profilesByIds(myFriendships().filter { it.status == "accepted" }.map { if (it.requester == me) it.addressee else it.requester })
        }

        override suspend fun incomingRequests(): List<Person> = call {
            val me = me()
            profilesByIds(myFriendships().filter { it.status == "pending" && it.addressee == me }.map { it.requester })
        }

        override suspend fun outgoingRequests(): List<Person> = call {
            val me = me()
            profilesByIds(myFriendships().filter { it.status == "pending" && it.requester == me }.map { it.addressee })
        }

        override suspend fun search(query: String): List<SearchResult> {
            val q = query.trim()
            if (q.length < 2) return emptyList()
            return call {
                val rows = client.postgrest.rpc("search_profiles", buildJsonObject {
                    put("query", q)
                    put("max", 20)
                }).decodeList<ProfileRow>()
                val me = me()
                val friendships = if (rows.isEmpty()) emptyList() else myFriendships()
                rows.map { SearchResult(it.toPerson(::avatarUrl), relationshipFrom(friendships, me, it.id)) }
            }
        }

        override suspend fun relationship(userId: String): Relationship = call { relationshipFrom(myFriendships(), me(), userId) }

        override suspend fun sendRequest(userId: String): Relationship = call {
            val status = client.postgrest.rpc("send_friend_request", buildJsonObject { put("target", userId) }).decodeAs<String>()
            if (status == "accepted") Relationship.FRIENDS else Relationship.REQUEST_SENT
        }

        override suspend fun respond(requesterId: String, accept: Boolean) = call {
            client.postgrest.rpc("respond_to_friend_request", buildJsonObject {
                put("requester", requesterId)
                put("accept", accept)
            })
            Unit
        }

        override suspend fun cancelRequest(userId: String) = call {
            client.from("friendships").delete {
                filter {
                    eq("requester", me())
                    eq("addressee", userId)
                    eq("status", "pending")
                }
            }
            Unit
        }

        override suspend fun removeFriend(userId: String) = call {
            val me = me()
            client.from("friendships").delete {
                filter {
                    or {
                        and { eq("requester", me); eq("addressee", userId) }
                        and { eq("requester", userId); eq("addressee", me) }
                    }
                }
            }
            Unit
        }

        override suspend fun suggestedReaders(): List<SuggestedReader> = call {
            client.postgrest.rpc("suggested_readers").decodeList<SuggestedReaderRow>().map { it.toReader(::avatarUrl) }
        }
    }

    private val safety = object : SafetyService {
        override suspend fun block(userId: String) = call {
            client.postgrest.rpc("block_user", buildJsonObject { put("target", userId) })
            Unit
        }

        override suspend fun unblock(userId: String) = call {
            client.from("blocks").delete {
                filter {
                    eq("blocker", me())
                    eq("blocked", userId)
                }
            }
            Unit
        }

        override suspend fun blockedPeople(): List<Person> = call {
            client.postgrest.rpc("my_blocked_profiles").decodeList<ProfileRow>().map { it.toPerson(::avatarUrl) }
        }

        override suspend fun report(userId: String, reason: ReportReason, details: String?, activityId: Long?) = call {
            client.from("reports").insert(
                NewReportRow(me(), userId, reason.name.lowercase(), details?.trim()?.ifEmpty { null }, activityId)
            )
            Unit
        }
    }

    private fun page(rows: List<FeedRow>): FeedPage {
        val entries = rows.map { it.toEntry(::avatarUrl) }
        val next = entries.takeIf { it.size >= FEED_PAGE_SIZE }?.last()?.let { FeedCursor(it.createdAt, it.id) }
        return FeedPage(entries, next)
    }

    private fun JsonObjectBuilder.putCursor(cursor: FeedCursor?) {
        put("before_time", cursor?.createdAt?.toString())
        put("before_id", cursor?.id)
        put("max", FEED_PAGE_SIZE)
    }

    private val activity: ActivityService = object : ActivityService {
        override suspend fun publish(book: PublicBookState, before: PublicBookState?) = call {
            client.postgrest.rpc("publish_book", buildJsonObject {
                put("book", book.toBookJson())
                put("before", before?.toBeforeJson() ?: JsonNull)
            })
            Unit
        }

        override suspend fun unpublish(shareId: String) = call {
            client.postgrest.rpc("unpublish_book", buildJsonObject { put("id", shareId) })
            Unit
        }

        override suspend fun unpublishAll() = call {
            client.postgrest.rpc("unpublish_all")
            Unit
        }

        override suspend fun feed(scope: FeedScope, cursor: FeedCursor?): FeedPage = call {
            page(client.postgrest.rpc("feed", buildJsonObject {
                put("scope", scope.name.lowercase())
                putCursor(cursor)
            }).decodeList<FeedRow>())
        }

        override suspend fun personActivity(userId: String, cursor: FeedCursor?): FeedPage = call {
            page(client.postgrest.rpc("person_activity", buildJsonObject {
                put("person", userId)
                putCursor(cursor)
            }).decodeList<FeedRow>())
        }

        override suspend fun like(activityId: Long) = call {
            client.postgrest.rpc("like", buildJsonObject { put("activity", activityId) })
            Unit
        }

        override suspend fun unlike(activityId: Long) = call {
            client.postgrest.rpc("unlike", buildJsonObject { put("activity", activityId) })
            Unit
        }

        override suspend fun likers(activityId: Long): List<Person> = call {
            client.postgrest.rpc("likers", buildJsonObject { put("activity", activityId) })
                .decodeList<ProfileRow>().map { it.toPerson(::avatarUrl) }
        }

        override suspend fun bookScore(isbn: String): BookScore = call {
            client.postgrest.rpc("book_score", buildJsonObject { put("isbn", isbn) })
                .decodeList<BookScoreRow>().firstOrNull()?.toScore() ?: BookScore.NONE
        }

        override suspend fun bookReviews(isbn: String, skip: Int): List<BookReview> = call {
            client.postgrest.rpc("book_reviews", buildJsonObject {
                put("isbn", isbn)
                put("skip", skip)
            }).decodeList<BookReviewRow>().map { it.toReview(::avatarUrl) }
        }

        override suspend fun communitySuggestions(likedIsbns: List<String>): List<CommunitySuggestion> = call {
            if (likedIsbns.isEmpty()) return@call emptyList()
            client.postgrest.rpc("community_suggestions", buildJsonObject {
                put("liked", JsonArray(likedIsbns.take(50).map { JsonPrimitive(it) }))
            }).decodeList<CommunitySuggestionRow>().map { it.toSuggestion() }
        }

        override suspend fun friendsReading(): List<FriendReading> = call {
            client.postgrest.rpc("friends_reading").decodeList<FriendReadingRow>().map { it.toReading(::avatarUrl) }
        }

        override suspend fun friendsWithBook(isbn: String): List<FriendWithBook> = call {
            client.postgrest.rpc("friends_with_book", buildJsonObject { put("p_isbn", isbn) })
                .decodeList<FriendWithBookRow>().map { it.toFriend(::avatarUrl) }
        }
    }

    val services = SocialServices(auth, profiles, friends, safety, activity, SupabaseSync(client))
}
