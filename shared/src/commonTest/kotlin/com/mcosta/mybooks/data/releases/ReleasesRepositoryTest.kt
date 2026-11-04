package com.mcosta.mybooks.data.releases

import com.mcosta.mybooks.testing.fixture
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import okio.Path.Companion.toPath
import okio.ForwardingFileSystem
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class ReleasesRepositoryTest {
    private val base = "https://pages.test/releases/"
    private var now = Instant.parse("2026-11-03T10:00:00Z")
    private var offline = false
    private var notPublished = false
    private val requests = mutableListOf<String>()
    private val files = mutableMapOf(
        "index.json" to fixture("releases_index.json"),
        "2026-10.json" to fixture("releases_2026-10.json"),
    )

    private val engine = MockEngine { request ->
        val name = request.url.encodedPath.substringAfterLast('/')
        requests += name
        if (offline) throw IOException("no network")
        val body = files[name]
        if (notPublished || body == null) respond("", HttpStatusCode.NotFound)
        else respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
    }
    private val fs = FakeFileSystem()
    private val repo = ReleasesRepository(ReleasesClient(HttpClient(engine) { expectSuccess = false }, base), fs, "/releases".toPath(), { now })

    @Test
    fun parsesFixtureIndexInGenreOrder() = runTest {
        val october = repo.months().value.single()
        assertEquals("2026-10", october.month)
        assertTrue(october.complete)
        assertEquals(8, october.total)
        assertEquals(
            listOf(
                ReleaseGenre.CRIME to 1, ReleaseGenre.SCIFI to 1, ReleaseGenre.NOVEL to 2, ReleaseGenre.POETRY to 1,
                ReleaseGenre.THEATRE to 1, ReleaseGenre.CHILDREN to 1, ReleaseGenre.HISTORY to 1,
            ),
            october.genres,
        )
    }

    @Test
    fun unknownGenreKeyIsOther() = runTest {
        files["index.json"] = """{"version":1,"updated":"x","months":[{"month":"2026-11","complete":false,"total":3,"genres":{"zines":2,"other":1}}]}"""
        assertEquals(listOf(ReleaseGenre.OTHER to 3), repo.months().value.single().genres)
    }

    @Test
    fun freshCacheSkipsNetwork() = runTest {
        repo.months()
        repo.books("2026-10")
        requests.clear()
        now += 5.hours
        repo.months()
        now += 18.hours                       // past month: kept for 24 h
        repo.books("2026-10")
        assertEquals(emptyList(), requests)
    }

    @Test
    fun staleCacheRefetches() = runTest {
        repo.months()
        requests.clear()
        now += 7.hours
        val again = repo.months()
        assertEquals(listOf("index.json"), requests)
        assertFalse(again.offline)
        assertEquals(now, again.savedAt)
    }

    @Test
    fun offlineReturnsSavedCopyFlagged() = runTest {
        val first = repo.months()
        now += 7.hours
        offline = true
        val cached = repo.months()
        assertTrue(cached.offline)
        assertEquals(first.savedAt, cached.savedAt)
        assertEquals(first.value, cached.value)
    }

    @Test
    fun offlineWithoutCopyThrows() = runTest {
        offline = true
        val e = assertFailsWith<ReleasesUnavailable> { repo.months() }
        assertFalse(e.notPublished)
    }

    @Test
    fun notFoundIsNotPublished() = runTest {
        notPublished = true
        assertTrue(assertFailsWith<ReleasesUnavailable> { repo.months() }.notPublished)
    }

    @Test
    fun booksSortedIgnoringAccents() = runTest {
        files["2026-11.json"] = """{"version":1,"month":"2026-11","updated":"x","books":[
            {"id":"2","isbn":"9789895682799","title":"Barco","authors":[],"translators":[],"year":2026,"genre":"novel"},
            {"id":"1","isbn":"9789727723973","title":"Árvore","authors":["A"],"translators":[],"year":2026,"genre":"novel"}]}"""
        assertEquals(listOf("Árvore", "Barco"), repo.books("2026-11").value.map { it.title })
    }

    @Test
    fun monthFileFieldsParsed() = runTest {
        val casa = repo.books("2026-10").value.single { it.id == "2293200" }
        assertEquals("9789895682799", casa.isbn)
        assertEquals(listOf("Thomas Bernhard"), casa.authors)
        assertEquals("Documenta", casa.publisher)
        assertEquals(ReleaseGenre.NOVEL, casa.genreType)
        assertEquals(casa, repo.book("2026-10", "2293200"))
    }

    @Test
    fun urlsForIsbn() {
        assertEquals(
            listOf(
                StoreLink("Bertrand", "https://www.bertrand.pt/pesquisa/9789895682799"),
                StoreLink("Almedina", "https://www.almedina.net/catalogsearch/result/?q=9789895682799"),
                StoreLink("Wook", "https://www.wook.pt/pesquisa?keyword=9789895682799"),
                StoreLink("Fnac", "https://www.fnac.pt/SearchResult/ResultList.aspx?Search=9789895682799"),
            ),
            storeLinks("9789895682799"),
        )
    }

    @Test
    fun storeLinksForABookUseTheIsbnElseTitleAndFirstAuthor() {
        assertEquals(storeLinks("9789722120005"), storeLinksFor("9789722120005", "Caim", "José Saramago"))
        assertEquals(storeLinks("Caim José Saramago"), storeLinksFor(null, "Caim", "José Saramago, Tradutor"))
        assertEquals(storeLinks("Caim José Saramago"), storeLinksFor(" ", "Caim", "José Saramago"))
        assertEquals(storeLinks("Anónimo"), storeLinksFor(null, "Anónimo", ""))
    }

    @Test
    fun storeLinksSearchByTitleAndAuthorWithoutIsbn() {
        assertEquals(
            listOf(
                StoreLink("Bertrand", "https://www.bertrand.pt/pesquisa/Memorial%20do%20Convento%20Jos%C3%A9%20Saramago"),
                StoreLink("Almedina", "https://www.almedina.net/catalogsearch/result/?q=Memorial+do+Convento+Jos%C3%A9+Saramago"),
                StoreLink("Wook", "https://www.wook.pt/pesquisa?keyword=Memorial+do+Convento+Jos%C3%A9+Saramago"),
                StoreLink("Fnac", "https://www.fnac.pt/SearchResult/ResultList.aspx?Search=Memorial+do+Convento+Jos%C3%A9+Saramago"),
            ),
            storeLinks("Memorial do Convento José Saramago"),
        )
    }

    @Test
    fun monthLabelSoFar() {
        assertEquals("November 2026 (so far)", monthLabel("2026-11", complete = false))
        assertEquals("October 2026", monthLabel("2026-10", complete = true))
    }

    @Test
    fun invalidDownloadFallsBackToSavedCopy() = runTest {
        val first = repo.months()
        now += 7.hours
        files["index.json"] = "<html>Wi-Fi login</html>"
        val cached = repo.months()
        assertTrue(cached.offline)
        assertEquals(first.value, cached.value)
    }

    @Test
    fun saveFailureStillReturnsDownload() = runTest {
        val broken = object : ForwardingFileSystem(FakeFileSystem()) {
            override fun sink(file: okio.Path, mustCreate: Boolean): okio.Sink = throw okio.IOException("disk full")
        }
        val r = ReleasesRepository(ReleasesClient(HttpClient(engine) { expectSuccess = false }, base), broken, "/releases".toPath(), { now })
        assertEquals("2026-10", r.months().value.single().month)
    }

    @Test
    fun unreadableSavedCopyIsIgnored() = runTest {
        val broken = object : ForwardingFileSystem(FakeFileSystem()) {
            override fun source(file: okio.Path): okio.Source = throw okio.IOException("bad sector")
            override fun metadataOrNull(path: okio.Path) = okio.FileMetadata(isRegularFile = true)
        }
        val r = ReleasesRepository(ReleasesClient(HttpClient(engine) { expectSuccess = false }, base), broken, "/releases".toPath(), { now })
        assertEquals("2026-10", r.months().value.single().month)
    }

    @Test
    fun monthRefetchedWhenIndexTotalDiffers() = runTest {
        repo.months()
        repo.books("2026-10")
        requests.clear()
        now += 7.hours
        files["index.json"] = fixture("releases_index.json").replace("\"total\":8", "\"total\":9")
        repo.months()
        repo.books("2026-10")
        assertEquals(listOf("index.json", "2026-10.json"), requests)
    }

    @Test
    fun oldSavedMonthsDeletedAfterIndexRefresh() = runTest {
        files["2026-03.json"] = """{"version":1,"month":"2026-03","updated":"x","books":[]}"""
        repo.books("2026-03")
        assertTrue(fs.exists("/releases/2026-03.json".toPath()))
        repo.months()
        assertFalse(fs.exists("/releases/2026-03.json".toPath()))
        assertFalse(fs.exists("/releases/2026-03.at".toPath()))
        assertTrue(fs.exists("/releases/index.json".toPath()))
    }

    @Test
    fun savedMonthInfoComesFromIndex() = runTest {
        assertEquals(null, repo.savedMonth("2026-10"))
        repo.months()
        assertEquals(true, repo.savedMonth("2026-10")?.complete)
    }
}
