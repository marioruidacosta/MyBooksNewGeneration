package com.mcosta.mybooks

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import io.ktor.client.engine.mock.respondError
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import com.mcosta.mybooks.data.authors.AuthorBook
import com.mcosta.mybooks.data.releases.AllMonths
import com.mcosta.mybooks.data.releases.MonthBooks
import com.mcosta.mybooks.data.releases.ReleaseBook
import com.mcosta.mybooks.ui.library.OutsideSearchViewModel
import com.mcosta.mybooks.ui.library.Section
import com.mcosta.mybooks.social.model.ActivityKind
import com.mcosta.mybooks.social.model.FeedBook
import com.mcosta.mybooks.social.model.FeedEntry
import com.mcosta.mybooks.social.model.FriendReading
import com.mcosta.mybooks.social.model.Person
import com.mcosta.mybooks.ui.social.ActivityState
import com.mcosta.mybooks.ui.social.CommunityFeed
import com.mcosta.mybooks.ui.social.FeedCallbacks
import kotlin.time.Duration.Companion.minutes
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.ChallengeRepository
import com.mcosta.mybooks.data.PinnedTags
import com.mcosta.mybooks.data.QuoteRepository
import com.mcosta.mybooks.data.ebooks.Classic
import com.mcosta.mybooks.data.ebooks.ClassicsSource
import com.mcosta.mybooks.data.ebooks.EbookFiles
import com.mcosta.mybooks.data.ebooks.EbookMatcher
import com.mcosta.mybooks.data.ebooks.EbookRepository
import com.mcosta.mybooks.domain.EbookFormat
import com.mcosta.mybooks.domain.EbookMetadata
import com.mcosta.mybooks.platform.IncomingFile
import com.mcosta.mybooks.platform.Inspection
import com.mcosta.mybooks.ui.ebooks.AddEbookScreen
import com.mcosta.mybooks.ui.ebooks.AddEbookState
import com.mcosta.mybooks.ui.ebooks.AddEbookViewModel
import com.mcosta.mybooks.ui.ebooks.ClassicsScreen
import com.mcosta.mybooks.ui.ebooks.ClassicsViewModel
import okio.Buffer
import com.mcosta.mybooks.ui.scan.BatchScanScreen
import com.mcosta.mybooks.ui.scan.BatchScanViewModel
import com.mcosta.mybooks.ui.scan.ScanResult
import com.mcosta.mybooks.domain.BookFilter
import com.mcosta.mybooks.data.backup.BackupSerializer
import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.covers.PhotoShrinker
import com.mcosta.mybooks.data.db.createAndroidDatabase
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.platform.CoverCamera
import com.mcosta.mybooks.platform.DocumentPicker
import com.mcosta.mybooks.platform.IsbnScanner
import com.mcosta.mybooks.platform.PlatformServices
import com.mcosta.mybooks.platform.ScanOutcome
import com.mcosta.mybooks.ui.LocalCoverStore
import com.mcosta.mybooks.ui.LocalPlatformServices
import com.mcosta.mybooks.ui.activity.ReadingActivityScreen
import com.mcosta.mybooks.ui.activity.ReadingActivityViewModel
import com.mcosta.mybooks.ui.activity.YearReviewScreen
import com.mcosta.mybooks.ui.activity.YearReviewViewModel
import com.mcosta.mybooks.ui.detail.BookDetailScreen
import com.mcosta.mybooks.ui.detail.BookDetailViewModel
import com.mcosta.mybooks.ui.home.HomeScreen
import com.mcosta.mybooks.ui.home.HomeViewModel
import com.mcosta.mybooks.ui.library.LibraryScreen
import com.mcosta.mybooks.ui.library.LibraryViewModel
import com.mcosta.mybooks.ui.theme.MyBooksTheme
import com.mcosta.mybooks.ui.social.KeepLibraryCard
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import com.mcosta.mybooks.ui.social.SyncInfo
import com.mcosta.mybooks.ui.social.SyncStatusSection
import com.mcosta.mybooks.sync.SyncStatus
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.io.File

/**
 * The main screens, drawn with sample books and saved as images in build/screenshots, light and dark (CI keeps them
 * as an artifact). They're for looking at, not compared pixel by pixel: fonts differ between machines.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "screens-${System.nanoTime()}.db"
    private val db = createAndroidDatabase(context, dbName)
    private val challenges = ChallengeRepository(db.challengeDao())
    private val repo = BookRepository(db.bookDao(), object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
    }, challenges = challenges)
    private val today = LocalDate.parse("2026-11-04")
    private val out = File("build/screenshots").apply { mkdirs() }

    private val services = PlatformServices(
        scanner = object : IsbnScanner { override suspend fun scan() = ScanOutcome.Cancelled },
        camera = object : CoverCamera { override suspend fun takePhoto(): ByteArray? = null },
        shrinker = object : PhotoShrinker { override fun shrink(photo: ByteArray, maxSide: Int) = photo },
        documents = object : DocumentPicker {
            override suspend fun save(suggestedName: String, mime: String, bytes: ByteArray) = false
            override suspend fun open(mimeTypes: List<String>): ByteArray? = null
        },
    )

    private fun day(text: String) = LocalDate.parse(text).atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()

    /** A small library like a real one: reading, read with scores and dates, to read, to buy. */
    private fun sampleLibrary(): Long = runBlocking {
        val books = listOf(
            Book(title = "Princípio, meio, fim", authors = "Valeria Luiselli", status = ReadingStatus.READING, progressPercent = 26, pageCount = 384, publisher = "Alfaguara"),
            Book(title = "Jalan Jalan: Uma Leitura do Mundo", authors = "Afonso Cruz", status = ReadingStatus.READING, progressPercent = 15),
            Book(title = "Memorial do Convento", authors = "José Saramago", status = ReadingStatus.READ, rating = 5, dateRead = day("2026-10-24"), publisher = "Porto Editora", publishedYear = 1982, pageCount = 352, isbn = "9789720046857", description = "No século XVIII, Baltasar e Blimunda, a construção do convento de Mafra e o sonho da passarola.", tags = listOf("Nobel", "favourites", "clube de leitura")),
            Book(title = "Solaris", authors = "Stanisław Lem", status = ReadingStatus.READ, rating = 4, dateRead = day("2026-09-06"), publisher = "Antígona", tags = listOf("favourites"), lentTo = "Ana", lentOn = day("2026-10-28")),
            Book(title = "A Irmandade do Anel", authors = "J.R.R. Tolkien", status = ReadingStatus.READ, rating = 5, dateRead = day("2026-02-01"), series = "O Senhor dos Anéis", seriesNumber = "1", tags = listOf("favourites")),
            Book(title = "As Duas Torres", authors = "J.R.R. Tolkien", status = ReadingStatus.TO_READ, series = "O Senhor dos Anéis", seriesNumber = "2"),
            Book(title = "O Regresso do Rei", authors = "J.R.R. Tolkien", status = ReadingStatus.WISHLIST, series = "O Senhor dos Anéis", seriesNumber = "3"),
            Book(title = "O Castelo", authors = "Franz Kafka", status = ReadingStatus.READ, rating = 4, dateRead = day("2026-07-06"), publisher = "Relógio d'Água"),
            Book(title = "Stoner", authors = "John Williams", status = ReadingStatus.READ, rating = 5, dateRead = day("2026-04-12"), publisher = "D. Quixote"),
            Book(title = "Os Maias", authors = "Eça de Queirós", status = ReadingStatus.TO_READ, publisher = "Livros do Brasil"),
            Book(title = "Terra ou Lua", authors = "Jade Khoo", status = ReadingStatus.TO_READ, publisher = "ASA"),
            Book(title = "A Montanha Mágica", authors = "Thomas Mann", status = ReadingStatus.TO_READ, publisher = "D. Quixote"),
            Book(title = "Ensaio sobre a Cegueira", authors = "José Saramago", status = ReadingStatus.WISHLIST, publisher = "Porto Editora"),
        )
        repo.importJson(BackupSerializer.encode(books, 0))
        challenges.setGoal(2026, 25)
        repo.findByTitleAndAuthors("Memorial do Convento", "José Saramago")?.id ?: repo.findByIsbn("9789720046857")!!.id
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    private fun shoot(name: String, dark: Boolean, ready: () -> Boolean, act: () -> Unit = {}, content: @Composable () -> Unit) {
        if (dark) RuntimeEnvironment.setQualifiers("+night")
        compose.setContent {
            CompositionLocalProvider(
                LocalCoverStore provides CoverStore(FileSystem.SYSTEM, context.filesDir.toOkioPath() / "covers"),
                LocalPlatformServices provides services,
            ) {
                MyBooksTheme { content() }
            }
        }
        act()
        // Robolectric's clock stands still while the test waits; move it on so delayed work on the main thread runs.
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); ready() }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name-${if (dark) "dark" else "light"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun home(dark: Boolean) {
        sampleLibrary()
        val pinned = PinnedTags(null).apply { setPinned("favourites", true) }
        val vm = HomeViewModel(repo, challenges, pinnedTags = pinned, today = { today })
        shoot("home", dark, ready = { vm.state.value.loaded }) {
            HomeScreen(vm, SnackbarHostState(), onOpenBook = {}, onOpenShelf = {}, onOpenReadingActivity = {}, onAddManually = {}, onScan = {})
        }
    }

    private fun shelf(dark: Boolean) {
        sampleLibrary()
        val vm = LibraryViewModel(repo, null, ReadingStatus.READ)
        shoot("shelf-read", dark, ready = { vm.state.value.books.isNotEmpty() }) {
            LibraryScreen(vm, title = "Read", onBack = {}, onOpenBook = {})
        }
    }

    private fun bookPage(dark: Boolean) {
        val id = sampleLibrary()
        val quotes = QuoteRepository(db.quoteDao())
        runBlocking {
            quotes.add(id, "Sem a vontade não se levanta nem um convento nem uma passarola.", 152)
            quotes.add(id, "Os olhos de Blimunda viam por dentro das pessoas.", 78)
        }
        // The book has an EPUB on the phone: the Read button and the Ebook card show.
        val ebooks = EbookRepository(db.ebookDao(), db.bookmarkDao(), EbookFiles(FileSystem.SYSTEM, context.filesDir.toOkioPath() / "ebooks"), repo)
        runBlocking { ebooks.add(id, EbookFormat.EPUB, "memorial-do-convento.epub", Buffer().write(ByteArray(1_234_567))) }
        val vm = BookDetailViewModel(repo, id, CoroutineScope(SupervisorJob() + Dispatchers.Default), quoteRepo = quotes, ebookRepo = ebooks)
        shoot("book", dark, ready = { vm.book.value != null && vm.quotes.value.size == 2 && vm.ebook.value != null }) {
            BookDetailScreen(vm, onBack = {}, onEdit = {}, onRead = {}, onAddEbookFile = {})
        }
    }

    /** Free classics after a search: one downloaded (Read), one to download. */
    private fun classics(dark: Boolean) {
        sampleLibrary()
        val found = listOf(
            Classic(18220, "Os Maias: episodios da vida romantica", listOf("Eça de Queirós"), listOf("pt"), "https://g/18220.epub", null),
            Classic(27637, "A Cidade e as Serras", listOf("Eça de Queirós"), listOf("pt"), "https://g/27637.epub", null),
            Classic(1342, "Pride and Prejudice", listOf("Jane Austen"), listOf("en"), "https://g/1342.epub", null),
        )
        val source = object : ClassicsSource {
            override suspend fun search(query: String) = found
            override suspend fun download(classic: Classic, into: suspend (okio.Source) -> Unit) = into(Buffer().write(ByteArray(10)))
        }
        val ebooks = EbookRepository(db.ebookDao(), db.bookmarkDao(), EbookFiles(FileSystem.SYSTEM, context.filesDir.toOkioPath() / "ebooks"), repo)
        val vm = ClassicsViewModel(source, EbookMatcher(repo), ebooks, repo)
        vm.search("queirós")
        vm.download(found[1])
        shoot("classics", dark, ready = { found[1].id in vm.state.value.downloaded }) {
            ClassicsScreen(vm, onBack = {}, onOpenBook = {}, onRead = {})
        }
    }

    /** An Adobe-protected file: why it can't be read here, and adding the book without it. */
    private fun protectedFile(dark: Boolean) {
        sampleLibrary()
        val ebooks = EbookRepository(db.ebookDao(), db.bookmarkDao(), EbookFiles(FileSystem.SYSTEM, context.filesDir.toOkioPath() / "ebooks"), repo)
        val file = IncomingFile(context.cacheDir.resolve("ticket.acsm").apply { writeText("x") }.path, "URLLink.acsm", 1)
        val inspection = Inspection.AdobeProtected(EbookMetadata("O Ano da Morte de Ricardo Reis", listOf("Saramago, José"), null, "pt"))
        val vm = AddEbookViewModel(file, { inspection }, EbookMatcher(repo), ebooks, repo, FileSystem.SYSTEM)
        shoot("protected", dark, ready = { vm.state.value is AddEbookState.AdobeProtected }) {
            AddEbookScreen(vm, onBack = {}, onDone = { _, _ -> })
        }
    }

    /** The year in review card. */
    private fun yearReview(dark: Boolean) {
        sampleLibrary()
        val vm = YearReviewViewModel(repo, challenges, 2026)
        shoot("year-review", dark, ready = { vm.review.value != null }) {
            YearReviewScreen(vm, onBack = {}, onOpenBook = {})
        }
    }

    /** Scan several, after a few barcodes: added, already in the library, unknown. */
    private fun batchScan(dark: Boolean) {
        sampleLibrary()
        val finder = BookRepository(db.bookDao(), object : BookLookupService {
            override suspend fun lookup(isbn: String): LookupResult = when (isbn) {
                "9789722036504" -> LookupResult.Found(Book(title = "A Cidade e as Serras", authors = "Eça de Queirós", publisher = "Porto Editora"))
                "9780441013593" -> LookupResult.Found(Book(title = "Dune", authors = "Frank Herbert"))
                else -> LookupResult.NotFound
            }
        })
        val vm = BatchScanViewModel(finder)
        vm.scanned("9780441013593")
        vm.scanned("9789720046857")       // Memorial do Convento: already in the library
        vm.scanned("9789720000004")
        vm.setStatus(ReadingStatus.WISHLIST)
        vm.scanned("9789722036504")
        shoot("scan-several", dark, ready = { vm.state.value.items.none { it.result == ScanResult.Looking } }) {
            BatchScanScreen(vm, onBack = {}, onAddManually = {}, onOpenBook = {})
        }
    }

    /** A book in a series, with the series card. */
    private fun seriesBook(dark: Boolean) {
        sampleLibrary()
        val id = runBlocking { repo.findByTitleAndAuthors("As Duas Torres", "J.R.R. Tolkien")!!.id }
        val vm = BookDetailViewModel(repo, id, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        shoot("book-series", dark, ready = { vm.book.value != null && vm.seriesBooks.value.size == 3 }) {
            BookDetailScreen(vm, onBack = {}, onEdit = {})
        }
    }

    /** A tag's books, across the shelves, with the pin. */
    private fun tag(dark: Boolean) {
        sampleLibrary()
        val vm = LibraryViewModel(repo, filter = BookFilter.Tag("favourites"), pinnedTags = PinnedTags(null))
        shoot("tag", dark, ready = { vm.state.value.books.size == 3 }) {
            LibraryScreen(vm, title = "favourites", onBack = {}, onOpenBook = {})
        }
    }

    private fun readingActivity(dark: Boolean) {
        sampleLibrary()
        val vm = ReadingActivityViewModel(repo, challenges, today = { today })
        shoot("reading-activity", dark, ready = { vm.state.value.loaded }) {
            ReadingActivityScreen(vm, onBack = {}, onOpenBook = {}, onOpenYear = {})
        }
    }

    // Home and the book page are drawn tall, to show their lower sections.

    /** Search for "saramago": the library's two books, then New in Portugal and public sources, one already owned. */
    private fun searchEverywhere(dark: Boolean) {
        sampleLibrary()
        val vm = LibraryViewModel(repo, null, null)
        val lucidez = ReleaseBook("b1", "9789722116959", "Ensaio sobre a Lucidez", listOf("José Saramago"), publisher = "Porto Editora")
        val cegueira = ReleaseBook("b2", "9789722036504", "Ensaio sobre a Cegueira", listOf("José Saramago"), publisher = "Porto Editora")
        val outside = OutsideSearchViewModel(
            releases = { AllMonths(listOf(MonthBooks("2026-11", true, listOf(lucidez, cegueira))), emptyList(), null) },
            online = {
                listOf(
                    AuthorBook("Ensaio sobre a Lucidez", "José Saramago", 2004, "Caminho", "9789722116959", null, null, null),
                    AuthorBook("Seeing", "José Saramago", 2006, "Harvill Secker", "9781843432470", null, null, null),
                )
            },
            library = repo.observeBooks(null, ""),
        )
        shoot(
            "search-everywhere", dark,
            ready = { outside.state.value.online is Section.Results && outside.state.value.releases is Section.Results && outside.state.value.library != null },
            act = { compose.onNodeWithText("Search title, author or ISBN").performTextInput("saramago") },
        ) {
            LibraryScreen(vm, title = "Search", onBack = null, onOpenBook = {}, outside = outside)
        }
        for (text in listOf("In your library (2)", "New in Portugal", "Public sources", "✓ In your library")) compose.onNodeWithText(text).assertExists()
    }

    /** Swipes the book header (the horizontal pager) [times] panels to the left, once it is on screen. */
    private fun swipeHeader(times: Int) {
        val pager = androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange)
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); compose.onAllNodes(pager).fetchSemanticsNodes().isNotEmpty() }
        repeat(times) {
            compose.onAllNodes(pager)[0].performTouchInput { swipeLeft() }
            compose.waitForIdle()
        }
    }

    /** A New in Portugal book with the header swiped to the author: their biography and the library's books by them. */
    private fun releaseBookAuthor(dark: Boolean) {
        sampleLibrary()
        val dir = context.filesDir.toOkioPath() / "releases-shot"
        FileSystem.SYSTEM.createDirectories(dir)
        FileSystem.SYSTEM.write(dir / "2026-11.json") {
            writeUtf8("""{"version":1,"month":"2026-11","updated":"2026-11-03T03:00:00Z","books":[{"id":"7","isbn":"9789722116959","title":"Ensaio sobre a Lucidez","authors":["José Saramago"],"translators":[],"publisher":"Porto Editora","place":"Porto","year":2026,"genre":"novel"}]}""")
        }
        FileSystem.SYSTEM.write(dir / "2026-11.at") { writeUtf8("1") }
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { respondError(io.ktor.http.HttpStatusCode.NotFound) })
        val releases = com.mcosta.mybooks.data.releases.ReleasesRepository(com.mcosta.mybooks.data.releases.ReleasesClient(http), FileSystem.SYSTEM, dir)
        val bio = com.mcosta.mybooks.data.authors.AuthorInfo(
            "José Saramago", "escritor português, Nobel da Literatura de 1998 (1922-2010)",
            "José de Sousa Saramago foi um escritor, argumentista, teatrólogo, ensaísta, jornalista, dramaturgo, contista, romancista e poeta português.",
            null, "https://pt.wikipedia.org/wiki/Jos%C3%A9_Saramago",
        )
        val vm = com.mcosta.mybooks.ui.releases.ReleaseBookViewModel(releases, repo, "2026-11", "7", authors = { bio })
        shoot("release-book-author", dark, ready = { vm.author.value?.loading == false }, act = { swipeHeader(2) }) {
            com.mcosta.mybooks.ui.releases.ReleaseBookScreen(vm, onBack = {})
        }
        for (text in listOf("Memorial do Convento", "Where to buy")) compose.onNodeWithText(text, substring = true).assertExists()
    }

    /** A catalogue book (from Search's public sources) with the header swiped to its details. */
    private fun catalogBookDetails(dark: Boolean) {
        sampleLibrary()
        val book = AuthorBook("Ensaio sobre a Lucidez", "José Saramago", 2004, "Caminho", "9789722116959", null, "Num país sem nome, a maioria dos eleitores vota em branco.", 336)
        val vm = com.mcosta.mybooks.ui.detail.CatalogBookViewModel(repo, book, authors = { null })
        shoot("catalog-book-details", dark, ready = { vm.state.value.loaded }, act = { swipeHeader(1) }) {
            com.mcosta.mybooks.ui.detail.CatalogBookScreen(vm, onBack = {}, onOpenBook = {})
        }
        for (text in listOf("Publisher", "Caminho", "Pages", "336")) compose.onNodeWithText(text).assertExists()
    }

    /** Community: friends reading, a progress card with pages, a review of a book already read, a group of five. */
    private fun community(dark: Boolean) {
        val ana = Person("a", "ana", "Ana Silva", null)
        val rui = Person("r", "rui", "Rui Costa", null)
        val marta = Person("m", "marta", "Marta Lopes", null)
        val now = kotlin.time.Clock.System.now()
        val reliquia = FeedBook(
            "s1", "9789722036504", "A Relíquia", "Eça de Queirós", null,
            blurb = "Teodorico Raposo viaja à Terra Santa para agradar à tia beata e herdar a sua fortuna.", pages = 344, currentPage = 182,
        )
        val memorial = FeedBook("s2", "9789720046857", "Memorial do Convento", "José Saramago", null,
            blurb = "Baltasar e Blimunda, a passarola do padre Bartolomeu e a construção do convento de Mafra.", pages = 464)
        fun entry(id: Long, who: Person, kind: ActivityKind, book: FeedBook, minutes: Long, rating: Int? = null, progress: Int? = null, review: String? = null) =
            FeedEntry(id, who, kind, rating, progress, now - minutes.minutes, book, review, likeCount = if (id == 10L) 2 else 0, likedByMe = false)
        val added = listOf("Os Maias", "Ensaio sobre a Cegueira", "Livro do Desassossego", "A Cidade e as Serras", "Jalan Jalan")
            .mapIndexed { i, t -> entry(20L - i, marta, ActivityKind.WANT_TO_READ, FeedBook("g$i", null, t, "Vários", null), 300L + i) }
        val state = ActivityState(
            signedIn = true, myId = "me",
            entries = listOf(entry(10, ana, ActivityKind.PROGRESS, reliquia, 120, progress = 53)) + added +
                entry(5, rui, ActivityKind.REVIEWED, memorial, 600, rating = 4, review = "Blimunda é das personagens mais bonitas que já li. A passarola, o convento, tudo."),
            reading = listOf(
                FriendReading(ana, reliquia, 53, now),
                FriendReading(rui, FeedBook("r2", null, "O Desaparecido", "Franz Kafka", null), 26, now),
                FriendReading(marta, FeedBook("r3", null, "Jalan Jalan", "Afonso Cruz", null), 15, now),
            ),
            shelves = mapOf("s2" to ReadingStatus.READ),
        )
        shoot("community", dark, ready = { true }) { androidx.compose.material3.Surface { CommunityFeed(state, FeedCallbacks()) } }
        for (text in listOf("Friends are reading", "Want to read", "✓ In your library · Read", "added 5 books to To read")) {
            compose.onNodeWithText(text, substring = true).assertExists()
        }
        compose.onNodeWithText("is on page 182 of 344", substring = true).assertExists()
        compose.onNodeWithContentDescription("More for this book").assertExists()
        compose.onNodeWithContentDescription("Rated 4 out of 5").assertExists()
    }

    /** A book page's "Friends who have this book". */
    private fun friendsWithBook(dark: Boolean) {
        val friends = listOf(
            com.mcosta.mybooks.social.model.FriendWithBook(Person("a", "ana", "Ana Silva", null), ReadingStatus.READ, 5, 100, "Uma sátira deliciosa, e o Raposão é inesquecível."),
            com.mcosta.mybooks.social.model.FriendWithBook(Person("r", "rui", "Rui Costa", null), ReadingStatus.READING, null, 40, ""),
            com.mcosta.mybooks.social.model.FriendWithBook(Person("m", "marta", "Marta Lopes", null), ReadingStatus.TO_READ, null, null, ""),
        )
        shoot("friends-with-book", dark, ready = { true }) {
            androidx.compose.material3.Surface { com.mcosta.mybooks.ui.detail.FriendsWithBookSection(friends, onPerson = {}) }
        }
        for (text in listOf("Ana Silva read it", "is reading it · 40%", "wants to read it")) compose.onNodeWithText(text, substring = true).assertExists()
    }

    /** BookNerd suggesting books (light) and writing an abstract (dark). */
    private fun bookNerd(dark: Boolean, abstract: Boolean) {
        val greeting = com.mcosta.mybooks.ui.booknerd.BotMessage.BotText(0, "Olá! I'm BookNerd. I can suggest books, find you fellow readers, or write an abstract for a book idea.")
        val state = if (!abstract) {
            val books = listOf(
                com.mcosta.mybooks.data.suggestions.Suggestion(com.mcosta.mybooks.data.suggestions.SuggestionSource.AUTHORS, "k1", "Ensaio sobre a Lucidez", "José Saramago", null, "Because you loved Saramago"),
                com.mcosta.mybooks.data.suggestions.Suggestion(com.mcosta.mybooks.data.suggestions.SuggestionSource.NEW_IN_PORTUGAL, "k2", "A Cidade e as Serras", "Eça de Queirós", null, "New in Portugal this month"),
                com.mcosta.mybooks.data.suggestions.Suggestion(com.mcosta.mybooks.data.suggestions.SuggestionSource.COMMUNITY, "k3", "Jalan Jalan", "Afonso Cruz", null, "Readers like you loved it"),
            )
            com.mcosta.mybooks.ui.booknerd.BookNerdState(
                messages = listOf(
                    greeting,
                    com.mcosta.mybooks.ui.booknerd.BotMessage.UserText(1, "suggest a book"),
                    com.mcosta.mybooks.ui.booknerd.BotMessage.BotText(2, "Here are some books you might like:"),
                    com.mcosta.mybooks.ui.booknerd.BotMessage.BookCards(3, books),
                ),
                chips = listOf("More", "Something else"),
                added = mapOf("k2" to ReadingStatus.TO_READ),
            )
        } else {
            val idea = com.mcosta.mybooks.ui.booknerd.IdeaAnswers(
                com.mcosta.mybooks.ui.booknerd.Genre.MYSTERY, "Teodoro, a shy pharmacist", "Lisbon, in 1910",
                "he finds a letter he was never meant to read", com.mcosta.mybooks.ui.booknerd.Tone.DARK,
            )
            com.mcosta.mybooks.ui.booknerd.BookNerdState(
                messages = listOf(
                    "What genre is it?" to "Mystery",
                    "Who is the main character? (a name and a few words)" to idea.character,
                    "Where and when does it happen?" to idea.setting,
                    "What goes wrong, or what do they want?" to idea.conflict,
                    "What tone?" to "Dark",
                ).flatMapIndexed { i, (q, a) ->
                    listOf(com.mcosta.mybooks.ui.booknerd.BotMessage.BotText(2L * i, q), com.mcosta.mybooks.ui.booknerd.BotMessage.UserText(2L * i + 1, a))
                } + listOf(
                    com.mcosta.mybooks.ui.booknerd.BotMessage.AbstractCard(10, com.mcosta.mybooks.ui.booknerd.writeAbstract(idea, com.mcosta.mybooks.ui.booknerd.BotLanguage.EN, 0)),
                ),
                chips = listOf("Another version", "Start over"),
            )
        }
        shoot(if (abstract) "booknerd-abstract" else "booknerd-books", dark, ready = { true }) {
            androidx.compose.material3.Surface {
                com.mcosta.mybooks.ui.booknerd.BookNerdChat(state, com.mcosta.mybooks.ui.booknerd.BookNerdActions(onShare = {}))
            }
        }
        val expected = if (abstract) listOf("What genre is it?", "Where and when does it happen?", "Another version", "Copy", "Share", "Start over") else listOf("To read", "To buy", "More", "Something else", "In your library")
        for (text in expected) {
            assertTrue(text, compose.onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty())
        }
    }
    /** Settings → Account's sync line: synced; offline with changes waiting, covers full and changes set aside; failed. */
    private fun accountSync(dark: Boolean) {
        val now = 1_000_000_000L
        shoot("account-sync", dark, ready = { true }) {
            androidx.compose.material3.Surface {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.padding(16.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp),
                ) {
                    SyncStatusSection(SyncInfo(SyncStatus.Synced(now - 10_000)), now, onRetry = {})
                    SyncStatusSection(SyncInfo(SyncStatus.Offline(3), coversFull = true, setAside = 2), now, onRetry = {})
                    SyncStatusSection(SyncInfo(SyncStatus.Failed(3)), now, onRetry = {})
                }
            }
        }
        for (text in listOf("Synced just now", "Offline · 3 changes waiting", "Cover photos not syncing: storage full", "2 changes couldn't be synced", "Retry")) {
            compose.onNodeWithText(text).assertExists()
        }
    }

    /** The first sign-in on a phone that already has books. */
    private fun accountCombining(dark: Boolean) {
        shoot("account-combining", dark, ready = { true }) {
            androidx.compose.material3.Surface {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(16.dp)) {
                    SyncStatusSection(SyncInfo(SyncStatus.Syncing, merging = 120 to 340), 0L, onRetry = {})
                }
            }
        }
        compose.onNodeWithText("Combining your libraries… 120 of 340").assertExists()
    }

    /** Signing out while the library syncs. */
    private fun signOutDialog(dark: Boolean) {
        // Over a dimmed screen, as the dialog shows (Robolectric can't draw the dialog's own window).
        shoot("sign-out-dialog", dark, ready = { true }) {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f)).padding(40.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                KeepLibraryCard(onKeep = {}, onRemove = {})
            }
        }
        for (text in listOf("Keep a copy of your library on this device?", "Keep", "Remove")) compose.onNodeWithText(text).assertExists()
    }

    /** A book whose ebook was added on another device: its record synced, its file didn't. */
    private fun ebookElsewhere(dark: Boolean) {
        val id = sampleLibrary()
        runBlocking { db.ebookDao().upsert(com.mcosta.mybooks.data.db.EbookEntity(id, EbookFormat.EPUB, "memorial-do-convento.epub", 1_234_567, day("2026-10-02"))) }
        val ebooks = EbookRepository(db.ebookDao(), db.bookmarkDao(), EbookFiles(FileSystem.SYSTEM, context.filesDir.toOkioPath() / "ebooks"), repo)
        val vm = BookDetailViewModel(repo, id, CoroutineScope(SupervisorJob() + Dispatchers.Default), ebookRepo = ebooks)
        shoot("ebook-elsewhere", dark, ready = { vm.book.value != null && vm.ebook.value != null }) {
            BookDetailScreen(vm, onBack = {}, onEdit = {}, onRead = {}, onAddEbookFile = {}, ebookElsewhere = true)
        }
        compose.onNodeWithText("Ebook on another device").assertExists()
    }

    @Test fun accountSyncLight() = accountSync(dark = false)
    @Test fun accountCombiningDark() = accountCombining(dark = true)
    @Test fun signOutDialogLight() = signOutDialog(dark = false)
    @Config(qualifiers = TALL) @Test fun ebookElsewhereLight() = ebookElsewhere(dark = false)

    @Config(qualifiers = TALL) @Test fun homeLight() = home(dark = false)
    @Config(qualifiers = TALL) @Test fun homeDark() = home(dark = true)
    @Test fun shelfLight() = shelf(dark = false)
    @Test fun shelfDark() = shelf(dark = true)
    @Config(qualifiers = TALL) @Test fun bookPageLight() = bookPage(dark = false)
    @Config(qualifiers = TALL) @Test fun bookPageDark() = bookPage(dark = true)
    @Config(qualifiers = TALL) @Test fun seriesBookLight() = seriesBook(dark = false)
    @Test fun tagLight() = tag(dark = false)
    @Test fun scanSeveralLight() = batchScan(dark = false)
    @Config(qualifiers = TALL) @Test fun yearReviewLight() = yearReview(dark = false)
    @Config(qualifiers = TALL) @Test fun yearReviewDark() = yearReview(dark = true)
    @Test fun scanSeveralDark() = batchScan(dark = true)
    @Test fun classicsLight() = classics(dark = false)
    @Test fun protectedLight() = protectedFile(dark = false)
    @Test fun protectedDark() = protectedFile(dark = true)

    private companion object {
        const val TALL = "w411dp-h1600dp-xxhdpi"
    }
    @Test fun readingActivityLight() = readingActivity(dark = false)
    @Test fun readingActivityDark() = readingActivity(dark = true)
    @Config(qualifiers = TALL) @Test fun searchEverywhereLight() = searchEverywhere(dark = false)
    @Config(qualifiers = TALL) @Test fun releaseBookAuthorDark() = releaseBookAuthor(dark = true)
    @Config(qualifiers = TALL) @Test fun catalogBookDetailsLight() = catalogBookDetails(dark = false)
    @Config(qualifiers = TALL) @Test fun communityLight() = community(dark = false)
    @Config(qualifiers = TALL) @Test fun communityDark() = community(dark = true)
    @Test fun friendsWithBookLight() = friendsWithBook(dark = false)
    @Config(qualifiers = TALL) @Test fun booknerdBooksLight() = bookNerd(dark = false, abstract = false)
    @Config(qualifiers = TALL) @Test fun booknerdAbstractDark() = bookNerd(dark = true, abstract = true)
}
