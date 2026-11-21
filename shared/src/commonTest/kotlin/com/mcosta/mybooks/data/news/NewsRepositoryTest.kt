package com.mcosta.mybooks.data.news

import com.mcosta.mybooks.testing.fixture
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class NewsRepositoryTest {
    private var now = Instant.parse("2026-11-17T10:00:00Z")
    private var offline = false
    private var status = HttpStatusCode.OK
    private var newsBody = fixture("news.json")
    private var requests = 0
    private var latency = 0L
    private val engine = MockEngine { request ->
        requests++
        if (latency > 0) delay(latency)
        if (offline) throw IOException("no network")
        val body = when (request.url.encodedPath.substringAfterLast('/')) {
            "news.json" -> newsBody
            "events.json" -> fixture("news_events.json")
            else -> ""
        }
        if (status != HttpStatusCode.OK) respond("", status) else respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
    }
    private val fs = FakeFileSystem()
    private val repo = NewsRepository(NewsClient(HttpClient(engine) { expectSuccess = false }, "https://pages.test/news/"), fs, "/news".toPath(), { now })

    @Test
    fun parsesNewsJson() = runTest {
        val feed = repo.news().value
        assertEquals(6, feed.items.size)
        assertEquals(feed.items.sortedByDescending { it.published }, feed.items)
        val apel = feed.items.single { it.source == "apel" }
        assertEquals("", apel.summary)
        assertTrue(feed.items.any { it.image == null } && feed.items.any { it.image != null })
        assertEquals("The New York Times Books", feed.sourceName("nyt"))
        assertEquals("APEL", feed.sourceName("apel"))
        assertEquals("unknown", feed.sourceName("unknown"))
        assertTrue(feed.items.all { it.url.startsWith("https://") })
    }

    @Test
    fun dropsLinksThatAreNotHttps() = runTest {
        newsBody = """{"generatedAt":1,"sources":[],"items":[
            {"id":"a","title":"Fine","summary":"","url":"https://a.test/x","source":"s","lang":"en","published":2,"image":"http://a.test/i.png"},
            {"id":"b","title":"Bad","summary":"","url":"javascript:alert(1)","source":"s","lang":"en","published":1,"image":null}]}"""
        val items = repo.news().value.items
        assertEquals(listOf("Fine"), items.map { it.title })
        assertNull(items.single().image)
    }

    @Test
    fun cacheIsUsedWhenOffline() = runTest {
        val first = repo.news()
        assertFalse(first.offline)
        now += 120.minutes
        offline = true
        val second = repo.news()
        assertTrue(second.offline)
        assertEquals(first.value, second.value)
        assertEquals(first.savedAt, second.savedAt)
        // A 404 (not published) also falls back to the saved copy, and so does a login page answering instead.
        offline = false
        status = HttpStatusCode.NotFound
        assertTrue(repo.news(force = true).offline)
        newsBody = "<html>Wi-Fi login</html>"
        status = HttpStatusCode.OK
        assertTrue(repo.news(force = true).offline)
        assertEquals(first.value, repo.news().value)
    }

    @Test
    fun nothingSavedAndOfflineIsUnavailable() = runTest {
        offline = true
        assertFalse(assertFailsWith<NewsUnavailable> { repo.news() }.notPublished)
        offline = false
        status = HttpStatusCode.NotFound
        assertTrue(assertFailsWith<NewsUnavailable> { repo.news() }.notPublished)
    }

    @Test
    fun refreshIsSkippedWithinThirtyMinutes() = runTest {
        repo.news()
        assertEquals(1, requests)
        now += 29.minutes
        repo.news()
        assertEquals(1, requests)
        now += 2.minutes
        repo.news()
        assertEquals(2, requests)
        // Pull to refresh asks again whatever the age.
        repo.news(force = true)
        assertEquals(3, requests)
    }

    @Test
    fun eventsComeFromTheCalendarFileAndFailQuietly() = runTest {
        assertEquals(13, repo.events().size)
        val other = NewsRepository(NewsClient(HttpClient(engine) { expectSuccess = false }, "https://pages.test/news/"), FakeFileSystem(), "/n".toPath(), { now })
        offline = true
        assertEquals(emptyList(), other.events())
    }

    private fun itemJson(id: String, title: String = "T $id", published: Long = 1_000, url: String = "https://a.test/$id", extra: String = "") =
        """{"id":"$id","title":"$title","summary":"","url":"$url","source":"s","lang":"en","published":$published$extra}"""

    private fun feedJson(vararg items: String) = """{"generatedAt":1,"sources":[],"items":[${items.joinToString(",")}]}"""

    @Test
    fun duplicateIdsDoNotCrashAndAreDropped() = runTest {
        newsBody = feedJson(itemJson("a", published = 3), itemJson("a", title = "Again", published = 2), itemJson("b", published = 1))
        assertEquals(listOf("a", "b"), repo.news().value.items.map { it.id })
        val events = """{"events":[
            {"name":"Fair","place":"x","month":3,"kind":"fair","lang":"en","url":"https://e.test/1"},
            {"name":"Fair","place":"y","month":4,"kind":"fair","lang":"en","url":"https://e.test/2"}]}"""
        val dupEvents = NewsRepository(
            NewsClient(HttpClient(MockEngine { respond(events, HttpStatusCode.OK) }) { expectSuccess = false }, "https://pages.test/news/"),
            FakeFileSystem(), "/e".toPath(), { now },
        )
        assertEquals(1, dupEvents.events().size)
    }

    @Test
    fun aMalformedItemIsDroppedNotTheFeed() = runTest {
        newsBody = feedJson(
            itemJson("a", published = 3),
            """{"id":"x","summary":"","url":"https://a.test/x","source":"s","lang":"en","published":2}""",
            """{"id":"y","title":"Y","summary":"","url":"https://a.test/y","source":"s","lang":"en","published":"x"}""",
            itemJson("b", published = 1),
        )
        assertEquals(listOf("a", "b"), repo.news().value.items.map { it.id })
    }

    @Test
    fun aWrongTopLevelStillFailsTheWholeFile() = runTest {
        newsBody = """{"items":5}"""
        assertFailsWith<NewsUnavailable> { repo.news() }
    }

    @Test
    fun capsTheItemsAndTheTextAndClampsFutureDates() = runTest {
        val long = "word ".repeat(100)
        val future = now.toEpochMilliseconds() + 400.days.inWholeMilliseconds
        newsBody = feedJson(
            *(1..305).map { itemJson("i$it", published = it.toLong()) }.toTypedArray(),
            """{"id":"long","title":"$long","summary":"$long$long","url":"https://a.test/l","source":"s","lang":"en","published":$future}""",
        )
        val items = repo.news().value.items
        assertEquals(300, items.size)
        val top = items.first()
        assertEquals("long", top.id)
        assertTrue(top.title.length <= 200 && top.title.endsWith("…"))
        assertTrue(top.summary.length <= 280 && top.summary.endsWith("…"))
        assertEquals(now.toEpochMilliseconds() + 1.days.inWholeMilliseconds, top.published)
    }

    @Test
    fun aResponseOverTwoMegabytesIsRejected() = runTest {
        newsBody = feedJson(itemJson("a", extra = ""","pad":"${"x".repeat(2_100_000)}""""))
        assertFailsWith<NewsUnavailable> { repo.news() }
        val declared = NewsRepository(
            NewsClient(HttpClient(MockEngine { respond("x".repeat(3_000_000), HttpStatusCode.OK, headersOf("Content-Length", "3000000")) }) { expectSuccess = false }, "https://pages.test/news/"),
            FakeFileSystem(), "/h".toPath(), { now },
        )
        assertFailsWith<NewsUnavailable> { declared.news() }
    }

    @Test
    fun aTruncatedCacheIsIgnoredAndHealed() = runTest {
        fs.createDirectories("/news".toPath())
        fs.write("/news/news.json".toPath()) { writeUtf8("""{"generatedAt":1,"items":[{"id":"a","ti""") }
        fs.write("/news/news.at".toPath()) { writeUtf8(now.toEpochMilliseconds().toString()) }
        val feed = repo.news()
        assertFalse(feed.offline)
        assertEquals(6, feed.value.items.size)
        assertEquals(1, requests)
        assertEquals(6, repo.news().value.items.size)
        assertEquals(1, requests)
        assertTrue(fs.list("/news".toPath()).none { it.name.endsWith(".tmp") })
        // And with no network the broken copy is not shown either.
        fs.write("/news/news.json".toPath()) { writeUtf8("""{"generatedAt""") }
        offline = true
        now += 60.minutes
        assertFailsWith<NewsUnavailable> { repo.news() }
    }

    @Test
    fun aClockSetBackMakesTheSavedCopyStale() = runTest {
        repo.news()
        now -= 10.minutes
        repo.news()
        assertEquals(2, requests)
    }

    @Test
    fun twoRefreshesAtOnceDownloadOnce() = runTest {
        latency = 500
        // Home and Book news both ask at start-up: one download, and both get the same fresh copy.
        val both = listOf(async { repo.news() }, async { repo.news() }).awaitAll()
        assertEquals(1, requests)
        assertEquals(both[0].value, both[1].value)
        assertFalse(both.any { it.offline })
        // Two pull-to-refreshes at once (forced) also share one download.
        now += 5.minutes
        val forced = listOf(async { repo.news(force = true) }, async { repo.news(force = true) }).awaitAll()
        assertEquals(2, requests)
        assertEquals(forced[0].value, forced[1].value)
        // A later forced refresh is its own download again.
        repo.news(force = true)
        assertEquals(3, requests)
        // The calendar is serialised too.
        val events = listOf(async { repo.events() }, async { repo.events() }).awaitAll()
        assertEquals(4, requests)
        assertEquals(events[0], events[1])
    }

    @Test
    fun theAppAndTheWebsiteDropTheSameStoriesAndEvents() = runTest {
        newsBody = feedJson(
            itemJson("ok", published = 5),
            itemJson("fr").replace("\"lang\":\"en\"", "\"lang\":\"fr\""),
            itemJson("blank", title = "   "),
            itemJson("pt", published = 4).replace("\"lang\":\"en\"", "\"lang\":\"pt\""),
        )
        assertEquals(listOf("ok", "pt"), repo.news().value.items.map { it.id })
        val events = """{"events":[
            {"name":"Fair","place":"x","month":3,"kind":"fair","lang":"en","url":"https://e.test/1"},
            {"name":"Plain","place":"x","month":3,"kind":"fair","lang":"en","url":"http://e.test/1"},
            {"name":"Script","place":"x","month":3,"kind":"fair","lang":"en","url":"javascript:alert(1)"},
            {"name":"  ","place":"x","month":3,"kind":"fair","lang":"en","url":"https://e.test/3"}]}"""
        val only = NewsRepository(
            NewsClient(HttpClient(MockEngine { respond(events, HttpStatusCode.OK) }) { expectSuccess = false }, "https://pages.test/news/"),
            FakeFileSystem(), "/e2".toPath(), { now },
        )
        assertEquals(listOf("Fair"), only.events().map { it.name })
    }
}
