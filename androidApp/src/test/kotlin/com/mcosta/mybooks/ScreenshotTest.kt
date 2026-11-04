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
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.ChallengeRepository
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
            Book(title = "Memorial do Convento", authors = "José Saramago", status = ReadingStatus.READ, rating = 5, dateRead = day("2026-10-24"), publisher = "Porto Editora", publishedYear = 1982, pageCount = 352, isbn = "9789720046857", description = "No século XVIII, Baltasar e Blimunda, a construção do convento de Mafra e o sonho da passarola."),
            Book(title = "Solaris", authors = "Stanisław Lem", status = ReadingStatus.READ, rating = 4, dateRead = day("2026-09-06"), publisher = "Antígona"),
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

    private fun shoot(name: String, dark: Boolean, ready: () -> Boolean, content: @Composable () -> Unit) {
        if (dark) RuntimeEnvironment.setQualifiers("+night")
        compose.setContent {
            CompositionLocalProvider(
                LocalCoverStore provides CoverStore(FileSystem.SYSTEM, context.filesDir.toOkioPath() / "covers"),
                LocalPlatformServices provides services,
            ) {
                MyBooksTheme { content() }
            }
        }
        // Robolectric's clock stands still while the test waits; move it on so delayed work on the main thread runs.
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); ready() }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name-${if (dark) "dark" else "light"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun home(dark: Boolean) {
        sampleLibrary()
        val vm = HomeViewModel(repo, challenges, today = { today })
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
        val vm = BookDetailViewModel(repo, id, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        shoot("book", dark, ready = { vm.book.value != null }) {
            BookDetailScreen(vm, onBack = {}, onEdit = {})
        }
    }

    private fun readingActivity(dark: Boolean) {
        sampleLibrary()
        val vm = ReadingActivityViewModel(repo, challenges, today = { today })
        shoot("reading-activity", dark, ready = { vm.state.value.loaded }) {
            ReadingActivityScreen(vm, onBack = {}, onOpenBook = {}, onOpenYear = {})
        }
    }

    @Test fun homeLight() = home(dark = false)
    @Test fun homeDark() = home(dark = true)
    @Test fun shelfLight() = shelf(dark = false)
    @Test fun shelfDark() = shelf(dark = true)
    @Test fun bookPageLight() = bookPage(dark = false)
    @Test fun bookPageDark() = bookPage(dark = true)
    @Test fun readingActivityLight() = readingActivity(dark = false)
    @Test fun readingActivityDark() = readingActivity(dark = true)
}
