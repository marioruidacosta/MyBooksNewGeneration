package com.mcosta.mybooks.data.releases

import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.testing.fixture
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ReleaseAlertsTest {
    private val october = Json { ignoreUnknownKeys = true }
        .decodeFromString<MonthJson>(fixture("releases_2026-10.json")).books

    /** Le Guin and Rowling are authors here; Presença is a favourite publisher (3 books); A casa de cal is owned. */
    private val library = listOf(
        Book(title = "Feiticeiro de Terramar", authors = "Ursula K. Le Guin", status = ReadingStatus.READ),
        Book(title = "Harry Potter", authors = "J.K. Rowling, Isabel Fraga", status = ReadingStatus.TO_READ),
        Book(title = "A", authors = "X", publisher = "Presença"),
        Book(title = "B", authors = "Y", publisher = "Presença"),
        Book(title = "C", authors = "Z", publisher = "presença"),
        Book(title = "A casa de cal", authors = "Thomas Bernhard", isbn = "9789895682799"),
    )

    @Test
    fun booksByYourAuthorsThenFromYourPublishers() {
        val alert = releaseAlert("2026-10", october, library)!!
        assertEquals(listOf("Os túmulos de Atuan"), alert.byAuthors.map { it.title })
        assertEquals(listOf("O ickabog", "A mais breve história do Japão"), alert.byPublishers.map { it.title })
        assertEquals(listOf("Ursula K. Le Guin"), alert.authors)
        assertEquals("New in Portugal · October 2026", alert.title)
        assertEquals("1 by Ursula K. Le Guin · 2 from Presença", alert.text)
        assertEquals(3, alert.count)
    }

    @Test
    fun aBookYouHaveIsNotAnnouncedAndNothingForYouIsNoAlert() {
        val bernhard = listOf(Book(title = "Extinção", authors = "Thomas Bernhard"), Book(title = "A casa de cal", authors = "Thomas Bernhard"))
        assertNull(releaseAlert("2026-10", october, bernhard))
        assertNull(releaseAlert("2026-10", october, emptyList()))
    }

    @Test
    fun manyNamesAreShortened() {
        val alert = ReleaseAlert("2026-10", october.take(4), emptyList(), listOf("A", "B", "C", "D"), emptyList())
        assertEquals("4 by A, B and 2 more", alert.text)
        assertEquals("1 by A and B", alert.copy(byAuthors = october.take(1), authors = listOf("A", "B")).text)
    }

    // The monthly check, against a New in Portugal that publishes October and, later, November.

    private var novemberDone = false
    private var offline = false
    private val engine = MockEngine { request ->
        if (offline) throw IOException("no network")
        val index = """{"version":1,"months":[
            {"month":"2026-11","complete":$novemberDone,"total":1,"genres":{"scifi":1}},
            {"month":"2026-10","complete":true,"total":8,"genres":{"novel":8}}]}"""
        val november = """{"month":"2026-11","books":[{"id":"9","isbn":"9789895680001","title":"A mão esquerda da escuridão",
            "authors":["Ursula K. Le Guin"],"publisher":"Saída de Emergência","genre":"scifi"}]}"""
        when (request.url.encodedPath.substringAfterLast('/')) {
            "index.json" -> respond(index, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            "2026-10.json" -> respond(fixture("releases_2026-10.json"), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            "2026-11.json" -> respond(november, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            else -> respond("", HttpStatusCode.NotFound)
        }
    }
    private var now = Instant.parse("2026-11-03T10:00:00Z")
    private val repo = ReleasesRepository(
        ReleasesClient(HttpClient(engine) { expectSuccess = false }, "https://pages.test/releases/"),
        FakeFileSystem(), "/releases".toPath(), { now }, io = Dispatchers.Unconfined,
    )
    private val stored = mutableMapOf<String, String>()
    private val prefs = object : Preferences {
        override fun get(key: String) = stored[key]
        override fun set(key: String, value: String) { stored[key] = value }
    }
    private val alerts = ReleaseAlerts(repo, { library }, prefs)

    @Test
    fun offUntilTurnedOnThenEachNewFinishedMonthOnce() = runTest {
        assertFalse(alerts.enabled)
        assertNull(alerts.check())

        alerts.setEnabled(true)
        assertTrue(alerts.enabled)
        assertNull(alerts.check())                 // October was already there when turned on

        novemberDone = true
        now = Instant.parse("2026-12-02T10:00:00Z")   // past the saved lists' age
        val alert = alerts.check()
        assertEquals("2026-11", alert?.month)
        assertEquals("1 by Ursula K. Le Guin", alert?.text)
        assertNull(alerts.check())                 // told once
    }

    @Test
    fun offlineChecksWaitForTheNetwork() = runTest {
        alerts.setEnabled(true)
        novemberDone = true
        offline = true
        now = Instant.parse("2026-12-02T10:00:00Z")
        assertNull(alerts.check())
        offline = false
        assertEquals("2026-11", alerts.check()?.month)
    }

    @Test
    fun turnedOffItStaysQuiet() = runTest {
        alerts.setEnabled(true)
        alerts.setEnabled(false)
        novemberDone = true
        now = Instant.parse("2026-12-02T10:00:00Z")
        assertNull(alerts.check())
    }
}
