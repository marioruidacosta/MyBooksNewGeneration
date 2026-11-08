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
import androidx.compose.ui.test.performTextInput
import com.mcosta.mybooks.data.authors.AuthorBook
import com.mcosta.mybooks.data.releases.AllMonths
import com.mcosta.mybooks.data.releases.MonthBooks
import com.mcosta.mybooks.data.releases.ReleaseBook
import com.mcosta.mybooks.ui.library.OutsideSearchViewModel
import com.mcosta.mybooks.ui.library.Section
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

    /** Search with no library match: New in Portugal and public sources below. */
    private fun searchEverywhere(dark: Boolean) {
        sampleLibrary()
        val vm = LibraryViewModel(repo, null, null)
        val lucidez = ReleaseBook("b1", "9789722036504", "Ensaio sobre a Lucidez", listOf("José Saramago"), publisher = "Porto Editora")
        val outside = OutsideSearchViewModel(
            releases = { AllMonths(listOf(MonthBooks("2026-11", true, listOf(lucidez))), emptyList(), null) },
            online = {
                listOf(
                    AuthorBook("Ensaio sobre a Lucidez", "José Saramago", 2004, "Caminho", "9789722116959", null, null, null),
                    AuthorBook("Seeing", "José Saramago", 2006, "Harvill Secker", "9781843432470", null, null, null),
                )
            },
        )
        shoot(
            "search-everywhere", dark,
            ready = { outside.state.value.online is Section.Results && outside.state.value.releases is Section.Results },
            act = { compose.onNodeWithText("Search title, author or ISBN").performTextInput("lucidez") },
        ) {
            LibraryScreen(vm, title = "Search", onBack = null, onOpenBook = {}, outside = outside)
        }
        for (text in listOf("New in Portugal", "Public sources", "Not here?", "Add it yourself")) compose.onNodeWithText(text).assertExists()
    }
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
}
