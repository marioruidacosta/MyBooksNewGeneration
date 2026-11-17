package com.mcosta.mybooks.data.news

import com.mcosta.mybooks.testing.fixture
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class NewsRepositoryTest {
    private var now = Instant.parse("2026-11-17T10:00:00Z")
    private var offline = false
    private var status = HttpStatusCode.OK
    private var newsBody = fixture("news.json")
    private var requests = 0
    private val engine = MockEngine { request ->
        requests++
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
}
