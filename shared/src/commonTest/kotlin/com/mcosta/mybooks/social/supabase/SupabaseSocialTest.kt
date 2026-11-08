package com.mcosta.mybooks.social.supabase

import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.social.SocialConfig
import com.mcosta.mybooks.social.SocialException
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.social.model.ActivityKind
import com.mcosta.mybooks.social.model.FeedBook
import com.mcosta.mybooks.social.model.FeedCursor
import com.mcosta.mybooks.social.model.FeedScope
import com.mcosta.mybooks.social.model.Person
import com.mcosta.mybooks.social.model.PublicBookState
import com.mcosta.mybooks.social.model.Relationship
import com.mcosta.mybooks.social.model.ReportReason
import com.mcosta.mybooks.social.model.Visibility
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.assertNull
import kotlin.time.Instant
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupabaseSocialTest : MainDispatcherTest() {
    private val baseUrl = "https://test.supabase.co"
    private val requests = mutableListOf<Pair<HttpRequestData, String>>()

    /** Responds by path; each path's responses are used in order. */
    private fun social(routes: Map<String, List<Pair<Int, String>>>): SupabaseSocial {
        val queues = routes.mapValues { ArrayDeque(it.value) }.toMutableMap()
        val engine = MockEngine { request -> respondFor(request, queues) }
        val client = supabaseClient(SocialConfig(baseUrl, "anon-key"), engine, persistSession = false)
        return SupabaseSocial(client, currentUserId = { "me" })
    }

    private fun MockRequestHandleScope.respondFor(
        request: HttpRequestData,
        queues: MutableMap<String, ArrayDeque<Pair<Int, String>>>,
    ) = run {
        val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
        requests += request to body
        val (status, json) = queues[request.url.encodedPath]?.removeFirstOrNull() ?: (200 to "[]")
        respond(json, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
    }

    @Test
    fun sendRequestCallsRpc() = runTest {
        val social = social(mapOf("/rest/v1/rpc/send_friend_request" to listOf(200 to "\"accepted\"")))
        assertEquals(Relationship.FRIENDS, social.services.friends.sendRequest("id-bruno"))
        val (request, body) = requests.single()
        assertEquals("/rest/v1/rpc/send_friend_request", request.url.encodedPath)
        assertTrue(body.replace(" ", "").contains("\"target\":\"id-bruno\""), body)
    }

    @Test
    fun searchMapsRows() = runTest {
        val social = social(
            mapOf(
                "/rest/v1/rpc/search_profiles" to listOf(
                    200 to """[{"id":"id-bruno","username":"bruno","display_name":"Bruno","avatar_path":null,"visibility":"friends"}]""",
                ),
                "/rest/v1/friendships" to listOf(200 to """[{"requester":"me","addressee":"id-bruno","status":"pending"}]"""),
            )
        )
        val result = social.services.friends.search(" ma ").single()
        assertEquals("bruno", result.person.username)
        assertEquals(Relationship.REQUEST_SENT, result.relationship)
        val rpcBody = requests.first { it.first.url.encodedPath.endsWith("search_profiles") }.second.replace(" ", "")
        assertTrue(rpcBody.contains("\"query\":\"ma\"") && rpcBody.contains("\"max\":20"), rpcBody)
    }

    @Test
    fun shortSearchMakesNoRequest() = runTest {
        assertTrue(social(emptyMap()).services.friends.search("m").isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun usernameTakenMapsToUsernameTaken() = runTest {
        val social = social(
            mapOf(
                "/rest/v1/profiles" to listOf(
                    409 to """{"code":"23505","message":"duplicate key value violates unique constraint \"profiles_username_key\"","details":null,"hint":null}""",
                ),
            )
        )
        val e = assertFailsWith<SocialException> { social.services.profiles.createProfile("bruno", "Bruno") }
        assertEquals(SocialException.Kind.USERNAME_TAKEN, e.kind)
    }

    @Test
    fun networkFailureIsOffline() = runTest {
        val engine = MockEngine { throw IOException("no route to host") }
        val social = SupabaseSocial(supabaseClient(SocialConfig(baseUrl, "anon-key"), engine, persistSession = false), currentUserId = { "me" })
        val e = assertFailsWith<SocialException> { social.services.friends.friends() }
        assertEquals(SocialException.Kind.OFFLINE, e.kind)
    }

    @Test
    fun avatarUrlBuiltFromPath() = runTest {
        val social = social(
            mapOf(
                "/rest/v1/profiles" to listOf(
                    200 to """[{"id":"id-bruno","username":"bruno","display_name":"Bruno","avatar_path":"id-bruno/avatar-1.jpg","visibility":"public"}]""",
                ),
            )
        )
        assertEquals(
            "$baseUrl/storage/v1/object/public/avatars/id-bruno/avatar-1.jpg",
            social.services.profiles.person("id-bruno")?.avatarUrl,
        )
    }

    @Test
    fun hiddenPersonIsNull() = runTest {
        val social = social(mapOf("/rest/v1/profiles" to listOf(200 to "[]")))
        assertEquals(null, social.services.profiles.person("id-blocker"))
    }

    @Test
    fun disabledConfigMeansNoSocial() {
        assertFalse(SocialConfig("", "").enabled)
        assertFalse(SocialConfig("https://x.supabase.co", " ").enabled)
        assertTrue(SocialConfig("https://x.supabase.co", "key").enabled)
    }

    private fun bodyOf(path: String) = requests.single { it.first.url.encodedPath == path }.second.replace(" ", "")

    private val book = PublicBookState("s1", "9789726084884", "T", "A", null, ReadingStatus.WISHLIST, null, null, "")

    @Test
    fun publishSendsBookAndBefore() = runTest {
        val social = social(mapOf("/rest/v1/rpc/publish_book" to listOf(204 to "")))
        social.services.activity.publish(book, book.copy(status = ReadingStatus.READ, rating = 3))
        val body = Json.parseToJsonElement(bodyOf("/rest/v1/rpc/publish_book"))
        val expected = Json.parseToJsonElement(
            """{"book":{"id":"s1","isbn":"9789726084884","title":"T","authors":"A","cover_url":null,"status":"to_buy",
               |"rating":null,"progress":null,"review":"","blurb":null,"pages":null,"current_page":null},
               |"before":{"status":"read","rating":3,"progress":null,"review":""}}""".trimMargin()
        )
        assertEquals(expected, body)
    }

    @Test
    fun publishWithoutBeforeSendsNull() = runTest {
        val social = social(mapOf("/rest/v1/rpc/publish_book" to listOf(204 to "")))
        social.services.activity.publish(book.copy(status = ReadingStatus.READING, progress = 40), null)
        val body = Json.parseToJsonElement(bodyOf("/rest/v1/rpc/publish_book")).jsonObject
        assertEquals(JsonNull, body["before"])
        assertEquals("reading", body["book"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(40, body["book"]!!.jsonObject["progress"]!!.jsonPrimitive.int)
    }

    @Test
    fun unpublishSendsId() = runTest {
        val social = social(mapOf("/rest/v1/rpc/unpublish_book" to listOf(204 to "")))
        social.services.activity.unpublish("s1")
        assertEquals("""{"id":"s1"}""", bodyOf("/rest/v1/rpc/unpublish_book"))
    }

    @Test
    fun feedSendsScopeAndCursor() = runTest {
        val social = social(mapOf("/rest/v1/rpc/feed" to listOf(200 to "[]")))
        val page = social.services.activity.feed(FeedScope.EVERYONE, FeedCursor(Instant.parse("2026-11-02T10:00:00Z"), 7))
        val body = Json.parseToJsonElement(bodyOf("/rest/v1/rpc/feed")).jsonObject
        assertEquals("everyone", body["scope"]!!.jsonPrimitive.content)
        assertEquals(Instant.parse("2026-11-02T10:00:00Z"), Instant.parse(body["before_time"]!!.jsonPrimitive.content))
        assertEquals(7L, body["before_id"]!!.jsonPrimitive.long)
        assertEquals(20, body["max"]!!.jsonPrimitive.int)
        assertTrue(page.entries.isEmpty())
        assertNull(page.next)
    }

    private fun feedRow(id: Long, kind: String = "finished", review: String? = null) =
        """{"id":$id,"kind":"$kind","rating":${if (kind == "rated") 4 else null},"progress":null,
           |"created_at":"2026-11-02T10:${(id % 60).toString().padStart(2, '0')}:00+00:00",
           |"actor_id":"id-bruno","username":"bruno","display_name":"Bruno","avatar_path":"id-bruno/avatar-1.jpg",
           |"book_id":"s$id","isbn":null,"title":"Book $id","authors":"A","cover_url":null,
           |"review":${review?.let { "\"$it\"" }},"like_count":2,"liked_by_me":true}""".trimMargin()

    @Test
    fun feedRowsMapToEntries() = runTest {
        val rows = (40L downTo 21L).map { feedRow(it, kind = if (it == 40L) "reviewed" else "rated", review = if (it == 40L) "Great" else null) }
        val social = social(mapOf("/rest/v1/rpc/feed" to listOf(200 to rows.joinToString(",", "[", "]"))))
        val page = social.services.activity.feed(FeedScope.FRIENDS)
        val first = page.entries.first()
        assertEquals(ActivityKind.REVIEWED, first.kind)
        assertEquals("Great", first.review)
        assertEquals("bruno", first.actor.username)
        assertEquals("$baseUrl/storage/v1/object/public/avatars/id-bruno/avatar-1.jpg", first.actor.avatarUrl)
        assertEquals(FeedBook("s40", null, "Book 40", "A", null), first.book)
        assertEquals(2, first.likeCount)
        assertTrue(first.likedByMe)
        assertEquals(ActivityKind.RATED, page.entries[1].kind)
        assertEquals(4, page.entries[1].rating)
        assertNull(page.entries[1].review)
        assertEquals(FeedCursor(Instant.parse("2026-11-02T10:21:00Z"), 21), page.next)
        assertEquals("friends", Json.parseToJsonElement(bodyOf("/rest/v1/rpc/feed")).jsonObject["scope"]!!.jsonPrimitive.content)
    }

    @Test
    fun publishSendsBlurbAndPages() = runTest {
        val social = social(mapOf("/rest/v1/rpc/publish_book" to listOf(204 to "")))
        social.services.activity.publish(book.copy(status = ReadingStatus.READING, blurb = "Blimunda.", pages = 464, currentPage = 93), null)
        val sent = Json.parseToJsonElement(bodyOf("/rest/v1/rpc/publish_book")).jsonObject["book"]!!.jsonObject
        assertEquals("Blimunda.", sent["blurb"]!!.jsonPrimitive.content)
        assertEquals(464, sent["pages"]!!.jsonPrimitive.int)
        assertEquals(93, sent["current_page"]!!.jsonPrimitive.int)
    }

    @Test
    fun feedReadsBookExtras() = runTest {
        val row = feedRow(5).dropLast(1) + ""","book_blurb":"Uma sátira.","book_pages":344,"book_current_page":182}"""
        val social = social(mapOf("/rest/v1/rpc/feed" to listOf(200 to "[$row]")))
        val book = social.services.activity.feed(FeedScope.FRIENDS).entries.single().book
        assertEquals(FeedBook("s5", null, "Book 5", "A", null, blurb = "Uma sátira.", pages = 344, currentPage = 182), book)
    }

    @Test
    fun friendsReadingParsed() = runTest {
        val row = """{"actor_id":"id-bruno","username":"bruno","display_name":"Bruno","avatar_path":null,"book_id":"s1",
            |"isbn":"9789722036504","title":"A Relíquia","authors":"Eça de Queirós","cover_url":null,"pages":344,
            |"current_page":137,"progress":40,"updated_at":"2026-11-08T10:00:00+00:00"}""".trimMargin()
        val social = social(mapOf("/rest/v1/rpc/friends_reading" to listOf(200 to "[$row]")))
        val reading = social.services.activity.friendsReading().single()
        assertEquals("bruno", reading.person.username)
        assertEquals(FeedBook("s1", "9789722036504", "A Relíquia", "Eça de Queirós", null, pages = 344, currentPage = 137), reading.book)
        assertEquals(40, reading.progress)
        assertEquals(Instant.parse("2026-11-08T10:00:00Z"), reading.updatedAt)
    }

    @Test
    fun friendsWithBookParsed() = runTest {
        val rows = """[{"actor_id":"id-bruno","username":"bruno","display_name":"Bruno","avatar_path":null,"status":"read",
            |"rating":5,"progress":100,"review":"Uma sátira deliciosa"},
            |{"actor_id":"id-fred","username":"fred","display_name":"Fred","avatar_path":null,"status":"to_buy",
            |"rating":null,"progress":null,"review":""}]""".trimMargin()
        val social = social(mapOf("/rest/v1/rpc/friends_with_book" to listOf(200 to rows)))
        val friends = social.services.activity.friendsWithBook("9789722039994")
        assertEquals(listOf(ReadingStatus.READ, ReadingStatus.WISHLIST), friends.map { it.status })
        assertEquals("Uma sátira deliciosa", friends[0].review)
        assertEquals(5, friends[0].rating)
        assertEquals("""{"p_isbn":"9789722039994"}""", bodyOf("/rest/v1/rpc/friends_with_book"))
    }

    @Test
    fun suggestedReadersParsed() = runTest {
        val row = """[{"id":"id-carla","username":"carla","display_name":"Carla","avatar_path":null,"shared_books":2,
            |"mutual_friends":1,"mutual_names":["Bruno"],"top_title":"Os Maias"}]""".trimMargin()
        val social = social(mapOf("/rest/v1/rpc/suggested_readers" to listOf(200 to row)))
        val r = social.services.friends.suggestedReaders().single()
        assertEquals("carla", r.person.username)
        assertEquals(listOf(2, 1), listOf(r.sharedBooks, r.mutualFriends))
        assertEquals(listOf("Bruno"), r.mutualNames)
        assertEquals("Os Maias", r.topTitle)
    }

    @Test
    fun personActivitySendsPerson() = runTest {
        val social = social(mapOf("/rest/v1/rpc/person_activity" to listOf(200 to "[${feedRow(1)}]")))
        val page = social.services.activity.personActivity("id-bruno")
        assertEquals(1, page.entries.size)
        assertNull(page.next)
        assertEquals("id-bruno", Json.parseToJsonElement(bodyOf("/rest/v1/rpc/person_activity")).jsonObject["person"]!!.jsonPrimitive.content)
    }

    @Test
    fun likeOwnMapsToNotAvailable() = runTest {
        val social = social(
            mapOf("/rest/v1/rpc/like" to listOf(400 to """{"code":"P0002","message":"entry not available","details":null,"hint":null}"""))
        )
        val e = assertFailsWith<SocialException> { social.services.activity.like(12) }
        assertEquals(SocialException.Kind.NOT_AVAILABLE, e.kind)
        assertEquals("""{"activity":12}""", bodyOf("/rest/v1/rpc/like"))
    }

    @Test
    fun unlikeSendsActivity() = runTest {
        val social = social(mapOf("/rest/v1/rpc/unlike" to listOf(204 to "")))
        social.services.activity.unlike(12)
        assertEquals("""{"activity":12}""", bodyOf("/rest/v1/rpc/unlike"))
    }

    @Test
    fun likersMapToPeople() = runTest {
        val social = social(
            mapOf("/rest/v1/rpc/likers" to listOf(200 to """[{"id":"id-ana","username":"ana","display_name":"Ana","avatar_path":null}]"""))
        )
        assertEquals(listOf(Person("id-ana", "ana", "Ana", null)), social.services.activity.likers(12))
    }

    @Test
    fun setShareActivityCallsRpcAndReturnsProfile() = runTest {
        val social = social(
            mapOf(
                "/rest/v1/rpc/set_share_activity" to listOf(204 to ""),
                "/rest/v1/profiles" to listOf(
                    200 to """[{"id":"me","username":"me","display_name":"Me","avatar_path":null,"visibility":"friends","share_activity":false}]""",
                ),
            )
        )
        val profile = social.services.profiles.setShareActivity(false)
        assertFalse(profile.shareActivity)
        assertEquals("""{"enabled":false}""", bodyOf("/rest/v1/rpc/set_share_activity"))
    }

    @Test
    fun personCarriesVisibility() = runTest {
        val social = social(
            mapOf(
                "/rest/v1/profiles" to listOf(
                    200 to """[{"id":"id-carla","username":"carla","display_name":"Carla","avatar_path":null,"visibility":"public"}]""",
                ),
            )
        )
        assertEquals(Visibility.PUBLIC, social.services.profiles.person("id-carla")?.visibility)
    }

    @Test
    fun reportSendsActivity() = runTest {
        val social = social(mapOf("/rest/v1/reports" to listOf(201 to "")))
        social.services.safety.report("id-bruno", ReportReason.SPAM, null, activityId = 12)
        assertTrue(bodyOf("/rest/v1/reports").contains("\"activity\":12"), bodyOf("/rest/v1/reports"))
    }

    @Test
    fun invalidDataMapsToRejected() = runTest {
        val social = social(
            mapOf("/rest/v1/rpc/publish_book" to listOf(400 to """{"code":"22023","message":"invalid book","details":null,"hint":null}"""))
        )
        val e = assertFailsWith<SocialException> { social.services.activity.publish(book, null) }
        assertEquals(SocialException.Kind.REJECTED, e.kind)
    }
}
