package com.mcosta.mybooks.ui.releases

import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.data.releases.NameCount
import com.mcosta.mybooks.data.releases.ReleaseFilter
import com.mcosta.mybooks.data.releases.ReleaseGenre
import com.mcosta.mybooks.data.releases.openLibraryCover
import com.mcosta.mybooks.data.releases.ReleasesClient
import com.mcosta.mybooks.data.releases.ReleasesRepository
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.testing.fixture
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class ReleasesViewModelsTest : MainDispatcherTest() {
    /** The mock HTTP engine answers on another thread: wait for loading to finish. */
    private suspend fun ReleasesViewModel.loaded() = also { state.first { !it.loading } }
    private suspend fun ReleaseGenreViewModel.loaded() = also { state.first { !it.loading } }

    private var now = Instant.parse("2026-11-03T10:00:00Z")
    private var offline = false
    private var notPublished = false
    private val files = mutableMapOf(
        "index.json" to """{"version":1,"updated":"x","months":[
            {"month":"2026-11","complete":false,"total":2,"genres":{"novel":1,"poetry":1}},
            {"month":"2026-10","complete":true,"total":8,"genres":{"children":1,"crime":1,"history":1,"novel":2,"poetry":1,"scifi":1,"theatre":1}}]}""",
        "2026-10.json" to fixture("releases_2026-10.json"),
    )
    private val engine = MockEngine { request ->
        if (offline) throw IOException("no network")
        val body = files[request.url.encodedPath.substringAfterLast('/')]
        if (notPublished || body == null) respond("", HttpStatusCode.NotFound)
        else respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
    }
    private val releases = ReleasesRepository(
        ReleasesClient(HttpClient(engine) { expectSuccess = false }, "https://pages.test/releases/"),
        FakeFileSystem(), "/releases".toPath(), { now }, io = Dispatchers.Unconfined,
    )
    private var lookup: LookupResult = LookupResult.NotFound
    private val dao = FakeBookDao()
    private val library = BookRepository(dao, object : BookLookupService {
        override suspend fun lookup(isbn: String) = lookup
    })

    @Test
    fun loadsMonthsAndSelectsNewest() = runTest {
        val vm = ReleasesViewModel(releases).loaded()
        val s = vm.state.value
        assertEquals(listOf("2026-11", "2026-10"), s.months.map { it.month })
        assertEquals("2026-11", s.selected?.month)
        assertNull(s.error)
        assertNull(s.savedAt)
    }

    @Test
    fun selectingMonthShowsItsGenres() = runTest {
        val vm = ReleasesViewModel(releases).loaded()
        vm.select("2026-10")
        assertEquals(
            listOf(ReleaseGenre.CRIME, ReleaseGenre.SCIFI, ReleaseGenre.NOVEL, ReleaseGenre.POETRY, ReleaseGenre.THEATRE, ReleaseGenre.CHILDREN, ReleaseGenre.HISTORY),
            vm.state.value.selected?.genres?.map { it.first },
        )
    }

    @Test
    fun offlineShowsSavedNote() = runTest {
        ReleasesViewModel(releases).loaded()
        now += 7.hours
        offline = true
        val vm = ReleasesViewModel(releases).loaded()
        assertEquals(Instant.parse("2026-11-03T10:00:00Z"), vm.state.value.savedAt)
        assertEquals(2, vm.state.value.months.size)
    }

    @Test
    fun notPublishedState() = runTest {
        notPublished = true
        val vm = ReleasesViewModel(releases).loaded()
        assertTrue(vm.state.value.notPublished)
        assertNull(vm.state.value.error)
    }

    @Test
    fun errorThenRetry() = runTest {
        offline = true
        val vm = ReleasesViewModel(releases).loaded()
        assertEquals("Couldn't load the new releases", vm.state.value.error)
        offline = false
        vm.retry()
        vm.loaded()
        assertNull(vm.state.value.error)
        assertEquals(2, vm.state.value.months.size)
    }

    @Test
    fun uncachedMonthOfflineShowsError() = runTest {
        ReleasesViewModel(releases).loaded()            // index saved; October never opened
        offline = true
        val vm = ReleaseGenreViewModel(releases, "2026-10", ReleaseGenre.NOVEL).loaded()
        assertEquals("Couldn't load the new releases", vm.state.value.error)
        offline = false
        vm.retry()
        vm.loaded()
        assertNull(vm.state.value.error)
        assertEquals(2, vm.state.value.books.size)
    }

    @Test
    fun genreListsOnlyThatGenreSorted() = runTest {
        val vm = ReleaseGenreViewModel(releases, "2026-10", ReleaseGenre.NOVEL).loaded()
        assertEquals(listOf("A casa de cal", "A frágil luz do mundo"), vm.state.value.books.map { it.title })
    }

    @Test
    fun bookShowsStoresAndLookupDescription() = runTest {
        lookup = LookupResult.Found(Book(title = "A casa de cal", description = "Um romance.", coverUrl = "https://books.google.com/c.jpg"))
        releases.books("2026-10")
        val vm = ReleaseBookViewModel(releases, library, "2026-10", "2293200")
        val s = vm.state.value
        assertEquals("A casa de cal", s.book?.title)
        assertEquals("Um romance.", s.description)
        assertEquals("https://books.google.com/c.jpg", s.coverUrl)
        assertEquals(listOf("Bertrand", "Almedina", "Wook", "Fnac"), s.stores.map { it.name })
        assertEquals("https://www.wook.pt/pesquisa?keyword=9789895682799", s.stores[2].url)
    }

    @Test
    fun coverFallsBackToOpenLibrary() = runTest {
        releases.books("2026-10")
        val vm = ReleaseBookViewModel(releases, library, "2026-10", "2293200")
        assertEquals("https://covers.openlibrary.org/b/isbn/9789895682799-M.jpg?default=false", vm.state.value.coverUrl)
    }

    @Test
    fun bookWithoutTranslatorsHasNone() = runTest {
        releases.books("2026-10")
        val vm = ReleaseBookViewModel(releases, library, "2026-10", "2293200")
        assertEquals(emptyList(), vm.state.value.book?.translators)
    }

    @Test
    fun addToLibrarySavesToBuy() = runTest {
        lookup = LookupResult.Found(Book(title = "A casa de cal", coverUrl = "https://books.google.com/c.jpg"))
        releases.books("2026-10")
        val vm = ReleaseBookViewModel(releases, library, "2026-10", "2293200")
        assertFalse(vm.state.value.inLibrary)
        vm.addToLibrary(ReadingStatus.WISHLIST)
        val saved = assertNotNull(dao.getAll().singleOrNull())
        assertEquals("A casa de cal", saved.title)
        assertEquals("9789895682799", saved.isbn)
        assertEquals("Thomas Bernhard", saved.authors)
        assertEquals("Documenta", saved.publisher)
        assertEquals(2026, saved.publishedYear)
        assertEquals("https://books.google.com/c.jpg", saved.coverUrl)
        assertEquals(ReadingStatus.WISHLIST, saved.status)
        assertEquals("Added to To buy", vm.state.value.message)
        assertTrue(vm.state.value.inLibrary)
    }

    @Test
    fun alreadyInLibraryDetectedByIsbn() = runTest {
        library.save(Book(title = "A casa de cal", isbn = "9789895682799"))
        releases.books("2026-10")
        val vm = ReleaseBookViewModel(releases, library, "2026-10", "2293200")
        assertTrue(vm.state.value.inLibrary)
    }

    @Test
    fun genreScreenMonthCompletenessFromIndex() = runTest {
        ReleasesViewModel(releases).loaded()
        assertEquals(false, ReleaseGenreViewModel(releases, "2026-11", ReleaseGenre.NOVEL).loaded().state.value.monthComplete)
        assertEquals(true, ReleaseGenreViewModel(releases, "2026-10", ReleaseGenre.NOVEL).loaded().state.value.monthComplete)
    }

    @Test
    fun coverFromReleaseFile() = runTest {
        files["2026-11.json"] = """{"version":1,"month":"2026-11","updated":"x","books":[
            {"id":"7","isbn":"9789895682799","title":"Com capa","year":2026,"genre":"novel","cover":"https://rnod.bnportugal.gov.pt/ImagesBN/x.jpg"}]}"""
        releases.books("2026-11")
        val vm = ReleaseBookViewModel(releases, library, "2026-11", "7")
        assertEquals("https://rnod.bnportugal.gov.pt/ImagesBN/x.jpg", vm.state.value.coverUrl)
        assertEquals("https://rnod.bnportugal.gov.pt/ImagesBN/x.jpg", releases.books("2026-11").value.single().listCover)
        assertEquals(openLibraryCover("9789895682799"), releases.books("2026-10").value.first().listCover)
    }

    @Test
    fun publisherViewListsTheMonthsPublishers() = runTest {
        val vm = ReleasesViewModel(releases).loaded()
        vm.select("2026-10")
        vm.setView(ReleaseView.PUBLISHER)
        vm.state.first { !it.namesLoading }
        val names = vm.state.value.names
        assertEquals(8, names.sumOf { it.count })
        assertTrue(names.any { it.label == "Documenta" })
        vm.setQuery("documen")
        assertEquals(listOf("Documenta"), vm.state.value.shownNames.map { it.label })
    }

    @Test
    fun authorViewAndBackToGenre() = runTest {
        val vm = ReleasesViewModel(releases).loaded()
        vm.select("2026-10")
        vm.setView(ReleaseView.AUTHOR)
        vm.state.first { !it.namesLoading }
        assertTrue(vm.state.value.names.any { it.label == "Thomas Bernhard" })
        vm.setView(ReleaseView.GENRE)
        assertEquals(ReleaseView.GENRE, vm.state.value.view)
    }

    @Test
    fun authorBookList() = runTest {
        val vm = ReleaseGenreViewModel(releases, "2026-10", ReleaseFilter.Author("Thomas Bernhard")).loaded()
        assertEquals(listOf("A casa de cal"), vm.state.value.books.map { it.title })
    }

    @Test
    fun publisherBookList() = runTest {
        val vm = ReleaseGenreViewModel(releases, "2026-10", ReleaseFilter.Publisher("Documenta")).loaded()
        assertEquals(listOf("A casa de cal"), vm.state.value.books.map { it.title })
    }

    private val november = """{"version":1,"month":"2026-11","updated":"x","books":[
        {"id":"9001","isbn":"9789720000001","title":"Extinção","authors":["Thomas Bernhard"],"publisher":"Relógio d'Água","genre":"novel"},
        {"id":"9002","isbn":"9789720000002","title":"Contos","authors":["Edgar Allan Poe"],"publisher":"Presença","genre":"stories"}]}"""
    private suspend fun ReleasesSearchViewModel.loaded() = also { state.first { !it.loading } }

    @Test
    fun searchCountsAcrossAllMonths() = runTest {
        files["2026-11.json"] = november
        val vm = ReleasesSearchViewModel(releases).loaded()
        assertEquals(2, vm.state.value.loaded)
        assertEquals(2, vm.state.value.total)
        vm.setQuery("bernhard")
        assertEquals(listOf(NameCount("Thomas Bernhard", "Thomas Bernhard", 2)), vm.state.value.results.authors)
        vm.setQuery("RELOGIO")
        assertEquals(listOf(NameCount("Relógio d'Água", "Relógio d'Água", 2)), vm.state.value.results.publishers)
    }

    @Test
    fun searchMatchesAuthorsPublishersAndGenresAtOnce() = runTest {
        files["2026-11.json"] = november
        val vm = ReleasesSearchViewModel(releases).loaded()
        vm.setQuery("poe")
        val r = vm.state.value.results
        assertEquals(listOf("Edgar Allan Poe"), r.authors.map { it.label })
        assertEquals(emptyList(), r.publishers)
        assertEquals(listOf(NameCount("poetry", "Poetry", 1)), r.genres)
    }

    @Test
    fun blankSearchShowsNoResults() = runTest {
        files["2026-11.json"] = november
        val vm = ReleasesSearchViewModel(releases).loaded()
        vm.setQuery("  ")
        assertTrue(vm.state.value.results.isEmpty)
    }

    @Test
    fun searchReportsMissingMonthAndRetries() = runTest {
        val vm = ReleasesSearchViewModel(releases).loaded()     // no November file
        assertEquals(listOf("2026-11"), vm.state.value.missing)
        vm.setQuery("bernhard")
        assertEquals(1, vm.state.value.results.authors.single().count)
        files["2026-11.json"] = november
        vm.retry()
        vm.loaded()
        assertEquals(emptyList(), vm.state.value.missing)
        assertEquals(2, vm.state.value.results.authors.single().count)
    }

    @Test
    fun allMonthsBookListGroupedNewestFirst() = runTest {
        files["2026-11.json"] = november
        val vm = ReleaseGenreViewModel(releases, null, ReleaseFilter.Author("Thomas Bernhard")).loaded()
        val groups = vm.state.value.groups
        assertEquals(listOf("2026-11", "2026-10"), groups.map { it.month })
        assertEquals(listOf(false, true), groups.map { it.complete })
        assertEquals(listOf(listOf("Extinção"), listOf("A casa de cal")), groups.map { g -> g.books.map { it.title } })
    }

    @Test
    fun allMonthsGenreListSkipsMonthsWithoutBooks() = runTest {
        files["2026-11.json"] = november
        val vm = ReleaseGenreViewModel(releases, null, ReleaseFilter.Genre(ReleaseGenre.POETRY)).loaded()
        assertEquals(listOf("2026-10"), vm.state.value.groups.map { it.month })
        assertEquals(listOf("Marés"), vm.state.value.books.map { it.title })
    }

    private class MemoryPreferences : com.mcosta.mybooks.data.Preferences {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String) { values[key] = value }
    }

    @Test
    fun listsSortAndRememberTheChoice() = runTest {
        val prefs = MemoryPreferences()
        val vm = ReleaseGenreViewModel(releases, "2026-10", ReleaseFilter.Genre(ReleaseGenre.NOVEL), prefs).loaded()
        assertEquals(listOf("A casa de cal", "A frágil luz do mundo"), vm.state.value.books.map { it.title })
        vm.setSort(com.mcosta.mybooks.ui.library.LibrarySort.AUTHOR)                  // Bernhard, Vareille
        vm.setDescending(true)
        assertEquals(listOf("A frágil luz do mundo", "A casa de cal"), vm.state.value.books.map { it.title })
        vm.setSort(com.mcosta.mybooks.ui.library.LibrarySort.SCORE)                   // not offered: ignored
        assertEquals(com.mcosta.mybooks.ui.library.LibrarySort.AUTHOR, vm.state.value.sort.order)
        val again = ReleaseGenreViewModel(releases, "2026-10", ReleaseFilter.Genre(ReleaseGenre.NOVEL), prefs).loaded()
        assertEquals(com.mcosta.mybooks.ui.library.SortChoice(com.mcosta.mybooks.ui.library.LibrarySort.AUTHOR, descending = true), again.state.value.sort)
        assertEquals(listOf("A frágil luz do mundo", "A casa de cal"), again.state.value.books.map { it.title })
    }

    @Test
    fun allMonthsListsSortWithinEachMonth() = runTest {
        files["2026-11.json"] = november
        val vm = ReleaseGenreViewModel(releases, null, ReleaseFilter.Publisher("Presença")).loaded()
        vm.setDescending(true)                         // Z to A by title, articles ignored: "mais breve…" then "ickabog"
        val groups = vm.state.value.groups
        assertEquals(listOf("2026-11", "2026-10"), groups.map { it.month })
        assertEquals(listOf(listOf("Contos"), listOf("A mais breve história do Japão", "O ickabog")), groups.map { g -> g.books.map { it.title } })
    }
}
