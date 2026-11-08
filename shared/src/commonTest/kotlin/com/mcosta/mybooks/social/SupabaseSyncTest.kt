package com.mcosta.mybooks.social

import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.social.supabase.SupabaseSync
import com.mcosta.mybooks.social.supabase.supabaseClient
import com.mcosta.mybooks.sync.FakeSyncServer
import com.mcosta.mybooks.sync.SyncException
import com.mcosta.mybooks.sync.SyncRow
import com.mcosta.mybooks.sync.SyncTable
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SupabaseSyncTest : MainDispatcherTest() {
    private val requests = mutableListOf<Pair<HttpRequestData, String>>()

    private fun sync(status: Int = 200, json: String = "{}", fail: Throwable? = null): SupabaseSync {
        val engine = MockEngine { request ->
            requests += request to (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
            if (fail != null) throw fail
            respond(json, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return SupabaseSync(supabaseClient(SocialConfig("https://test.supabase.co", "anon-key"), engine, persistSession = false))
    }

    private fun book(id: String, title: String, time: Long, authors: String = "", isbn: String? = null) = SyncRow(
        SyncTable.BOOKS, id,
        buildMap {
            put("title", JsonPrimitive(title))
            put("authors", JsonPrimitive(authors))
            if (isbn != null) put("isbn", JsonPrimitive(isbn))
        },
        buildMap {
            put("title", time)
            put("authors", time)
            if (isbn != null) put("isbn", time)
        },
    )

    @Test
    fun pushSendsRowsAsSyncPushJson() = runTest {
        val api = sync(json = """{"applied":[{"table":"books","id":"u1"}],"set_aside":[{"table":"quotes","id":"q1","reason":"too long"}]}""")
        val result = api.push(listOf(SyncRow(SyncTable.BOOKS, "u1", mapOf("title" to JsonPrimitive("Dune")), mapOf("title" to 5L))))
        val (request, body) = requests.single()
        assertEquals("/rest/v1/rpc/sync_push", request.url.encodedPath)
        assertEquals(
            Json.parseToJsonElement("""{"changes":[{"table":"books","id":"u1","fields":{"title":"Dune"},"times":{"title":5}}]}"""),
            Json.parseToJsonElement(body),
        )
        assertEquals(listOf(SyncTable.BOOKS to "u1"), result.applied)
        assertEquals("too long", result.setAside.single().reason)
        assertEquals(SyncTable.QUOTES, result.setAside.single().table)
    }

    @Test
    fun pullParsesRowsAndLast() = runTest {
        val api = sync(
            json = """{"rows":[{"table":"books","id":"u1","fields":{"title":"Dune"},"times":{"title":5,"deleted":6},"deleted":true,"seq":42}],"last":42}""",
        )
        val page = api.pull(10, 100)
        val body = Json.parseToJsonElement(requests.single().second).jsonObject
        assertEquals(JsonPrimitive(10), body["after"])
        assertEquals(JsonPrimitive(100), body["max"])
        assertEquals("/rest/v1/rpc/sync_pull", requests.single().first.url.encodedPath)
        assertEquals(42L, page.last)
        val row = page.rows.single()
        assertEquals(SyncRow(SyncTable.BOOKS, "u1", mapOf("title" to JsonPrimitive("Dune"), "deleted" to JsonPrimitive(true)),
            mapOf("title" to 5L, "deleted" to 6L), 42L), row)
    }

    @Test
    fun mergeLibraryReturnsIdMap() = runTest {
        val api = sync(json = """{"ids":{"local1":"cloud1"},"set_aside":[{"id":"local2","reason":"bad"}]}""")
        val result = api.mergeLibrary(listOf(book("local1", "Dune", 1)))
        assertEquals("/rest/v1/rpc/merge_library", requests.single().first.url.encodedPath)
        assertEquals(mapOf("local1" to "cloud1"), result.ids)
        assertEquals("local2", result.setAside.single().id)
    }

    @Test
    fun expiredSessionIsAuth() = runTest {
        val api = sync(401, """{"code":"PGRST301","message":"JWT expired"}""")
        assertEquals(SyncException.Kind.AUTH, assertFailsWith<SyncException> { api.pull(0) }.kind)
    }

    @Test
    fun offlineIsOffline() = runTest {
        val api = sync(fail = IOException("no network"))
        assertEquals(SyncException.Kind.OFFLINE, assertFailsWith<SyncException> { api.pull(0) }.kind)
    }

    @Test
    fun storageFullIsStorageFull() = runTest {
        val api = sync(413, """{"statusCode":"413","error":"Payload too large","message":"The object exceeded the maximum allowed size"}""")
        assertEquals(SyncException.Kind.STORAGE_FULL, assertFailsWith<SyncException> { api.uploadCover("o", "b", byteArrayOf(1)) }.kind)
        assertTrue(requests.single().first.url.encodedPath.endsWith("/storage/v1/object/covers/o/b.jpg"))
    }

    @Test
    fun missingCoverIsNull() = runTest {
        val api = sync(404, """{"statusCode":"404","error":"not_found","message":"Object not found"}""")
        assertEquals(null, api.downloadCover("o", "b"))
    }

    @Test
    fun otherFailureIsOther() = runTest {
        val api = sync(500, """{"message":"boom"}""")
        assertEquals(SyncException.Kind.OTHER, assertFailsWith<SyncException> { api.pull(0) }.kind)
    }

    // ---- the fake must follow the server's rule (Task 1)

    private fun row(title: String, time: Long) = SyncRow(SyncTable.BOOKS, "u1", mapOf("title" to JsonPrimitive(title)), mapOf("title" to time))

    @Test
    fun fakeServerAppliesNewestPerField() = runTest {
        val fake = FakeSyncServer(clock = { 1_000L })
        fake.push(listOf(SyncRow(SyncTable.BOOKS, "u1", mapOf("title" to JsonPrimitive("A"), "notes" to JsonPrimitive("n")),
            mapOf("title" to 10L, "notes" to 10L))))
        fake.push(listOf(SyncRow(SyncTable.BOOKS, "u1", mapOf("title" to JsonPrimitive("B"), "notes" to JsonPrimitive("m")),
            mapOf("title" to 5L, "notes" to 20L))))
        fake.push(listOf(row("C", 10L))) // equal time keeps the stored value
        val stored = fake.pull(0).rows.single()
        assertEquals(JsonPrimitive("A"), stored.fields["title"])
        assertEquals(JsonPrimitive("m"), stored.fields["notes"])
        assertEquals(mapOf("title" to 10L, "notes" to 20L), stored.times)
    }

    @Test
    fun fakeServerCapsFutureTimesAndAcceptsAnyTimeForUntimedFields() = runTest {
        val fake = FakeSyncServer(clock = { 1_000L })
        fake.push(listOf(row("A", 999_999L)))
        assertEquals(61_000L, fake.pull(0).rows.single().times["title"])
        // A later edit beyond the capped time loses until the clock catches up.
        fake.push(listOf(row("B", 50_000L)))
        assertEquals(JsonPrimitive("A"), fake.pull(0).rows.single().fields["title"])
        fake.clock = { 100_000L }
        fake.push(listOf(row("B", 120_000L)))
        assertEquals(JsonPrimitive("B"), fake.pull(0).rows.single().fields["title"])
    }

    @Test
    fun fakeServerKeepsOwnersApartAndSeqIncreases() = runTest {
        val fake = FakeSyncServer(owner = "a")
        fake.push(listOf(row("A", 1)))
        fake.owner = "b"
        assertTrue(fake.pull(0).rows.isEmpty())
        fake.push(listOf(row("B", 1)))
        val seqB = fake.pull(0).rows.single().seq
        fake.owner = "a"
        fake.push(listOf(row("A2", 2)))
        val a = fake.pull(0).rows.single()
        assertTrue(a.seq > seqB)
        assertEquals(emptyList(), fake.pull(a.seq).rows)
    }

    @Test
    fun fakeServerMergesLibraryByIsbnThenTitleAndAuthor() = runTest {
        val fake = FakeSyncServer(clock = { 1_000L })
        fake.push(listOf(book("c1", "Ensaio sobre a Cegueira: romance", 1, "José Saramago, Outro", "111"), book("c2", "Dune", 1, "Frank Herbert")))
        val result = fake.mergeLibrary(listOf(
            book("l1", "Outro titulo", 1, "x", "111"),
            book("l2", "ensaio sobre a cegueira", 2, "Jose Saramago"),
            book("l3", "dune", 2, "frank herbert"),
            book("l4", "Dune", 2, "Someone Else"),
        ))
        assertEquals(mapOf("l1" to "c1", "l2" to "c1", "l3" to "c2", "l4" to "l4"), result.ids)
    }

    @Test
    fun fakeServerFailNextOnceAndPings() = runTest {
        val fake = FakeSyncServer()
        fake.failNext = SyncException(SyncException.Kind.OFFLINE, "down")
        assertFailsWith<SyncException> { fake.push(listOf(row("A", 1))) }
        fake.push(listOf(row("A", 1)))
        val pings = mutableListOf<Unit>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { fake.pings("owner").collect { pings += it } }
        fake.push(listOf(row("B", 2)))
        assertEquals(1, pings.size)
    }

}
