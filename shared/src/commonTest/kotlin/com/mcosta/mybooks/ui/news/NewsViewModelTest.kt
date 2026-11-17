package com.mcosta.mybooks.ui.news

import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.data.news.NewsClient
import com.mcosta.mybooks.data.news.NewsLanguage
import com.mcosta.mybooks.data.news.NewsRepository
import com.mcosta.mybooks.testing.fixture
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.io.IOException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import com.mcosta.mybooks.data.Preferences
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class NewsViewModelTest : MainDispatcherTest() {
    private var offline = false
    private var newsDown = false
    private var emptyFeed = false
    private var eventsGate: CompletableDeferred<Unit>? = null
    private val engine = MockEngine { request ->
        if (offline) throw IOException("no network")
        when (request.url.encodedPath.substringAfterLast('/')) {
            "news.json" -> when {
                newsDown -> respond("", HttpStatusCode.NotFound)
                emptyFeed -> respond("""{"generatedAt":1,"sources":[],"items":[]}""", HttpStatusCode.OK)
                else -> respond(fixture("news.json"), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
            "events.json" -> {
                eventsGate?.await()
                respond(fixture("news_events.json"), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
            else -> respond("", HttpStatusCode.NotFound)
        }
    }
    private val repo = NewsRepository(
        NewsClient(HttpClient(engine) { expectSuccess = false }, "https://pages.test/news/"),
        FakeFileSystem(), "/news".toPath(), { Instant.parse("2026-11-17T10:00:00Z") },
    )

    /** The mock HTTP engine answers on another thread: wait for loading to finish. */
    private suspend fun NewsViewModel.loaded() = also { state.first { !it.loading && !it.eventsLoading } }
    private fun viewModel(prefs: Preferences? = null) = NewsViewModel(repo, prefs, today = { LocalDate(2026, 11, 17) }, timeZone = TimeZone.UTC)

    private class MemoryPrefs : Preferences {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun set(key: String, value: String) { map[key] = value }
    }

    @Test
    fun homeCardShowsTheThreeNewest() = runTest {
        val s = viewModel().loaded().state.value
        assertEquals(3, s.latest.size)
        assertEquals(s.items.sortedByDescending { it.published }.take(3), s.latest)
        assertEquals("The New York Times Books", s.sourceName(s.latest.first().source))
    }

    @Test
    fun filtersByLanguage() = runTest {
        val vm = viewModel().loaded()
        vm.setLanguage(NewsLanguage.PT)
        val pt = vm.state.value
        assertTrue(pt.shown.isNotEmpty() && pt.shown.all { it.lang == "pt" })
        assertTrue(pt.days.flatMap { it.items }.all { it.lang == "pt" })
        // The home card ignores the chips.
        assertEquals(3, pt.latest.size)
        vm.setLanguage(NewsLanguage.EN)
        assertTrue(vm.state.value.shown.all { it.lang == "en" })
        vm.setLanguage(NewsLanguage.ALL)
        assertEquals(6, vm.state.value.shown.size)
    }

    @Test
    fun offlineWithNothingSavedSaysSoAndRetryLoads() = runTest {
        offline = true
        val vm = viewModel().loaded()
        assertEquals("Couldn't load the news. Try again later.", vm.state.value.error)
        assertTrue(vm.state.value.items.isEmpty())
        offline = false
        vm.retry()
        vm.state.first { !it.loading && !it.eventsLoading && it.items.isNotEmpty() }
        assertNull(vm.state.value.error)
        assertNull(vm.state.value.savedAt)
    }

    @Test
    fun savedCopyShowsItsDateWhenTheRefreshFails() = runTest {
        viewModel().loaded()
        offline = true
        val vm = viewModel().loaded()
        vm.refresh()
        assertNotNull(vm.state.value.savedAt)
        assertEquals(6, vm.state.value.items.size)
        assertNull(vm.state.value.error)
    }

    @Test
    fun rememberedLanguageComesBackNextTime() = runTest {
        val prefs = MemoryPrefs()
        viewModel(prefs).loaded().setLanguage(NewsLanguage.PT)
        assertEquals(NewsLanguage.PT, viewModel(prefs).loaded().state.value.language)
        prefs.map["news.language"] = "nonsense"
        assertEquals(NewsLanguage.ALL, viewModel(prefs).loaded().state.value.language)
    }

    @Test
    fun theHomeCardDoesNotWaitForTheCalendar() = runTest {
        val gate = CompletableDeferred<Unit>().also { eventsGate = it }
        val vm = viewModel()
        val s = vm.state.first { it.items.isNotEmpty() }
        assertTrue(s.eventsLoading && s.events.isEmpty())
        assertEquals(3, s.latest.size)
        gate.complete(Unit)
        assertTrue(vm.state.first { !it.eventsLoading }.events.isNotEmpty())
    }

    @Test
    fun theCalendarLoadsEvenWhenTheNewsFails() = runTest {
        newsDown = true
        val vm = viewModel()
        val s = vm.state.first { !it.loading && !it.eventsLoading }
        assertEquals("Couldn't load the news. Try again later.", s.error)
        assertEquals(13, s.events.size)
    }

    @Test
    fun anEmptyFeedIsLoadedNotAnError() = runTest {
        emptyFeed = true
        val s = viewModel().loaded().state.value
        assertTrue(s.loaded && s.items.isEmpty())
        assertNull(s.error)
    }
}
