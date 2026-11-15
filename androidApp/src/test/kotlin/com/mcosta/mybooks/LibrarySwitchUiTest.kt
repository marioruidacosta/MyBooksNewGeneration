package com.mcosta.mybooks

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.data.covers.PhotoShrinker
import com.mcosta.mybooks.data.db.createAndroidDatabase
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.library.LibraryFiles
import com.mcosta.mybooks.library.LibraryId
import com.mcosta.mybooks.platform.CoverCamera
import com.mcosta.mybooks.platform.DocumentPicker
import com.mcosta.mybooks.platform.IsbnScanner
import com.mcosta.mybooks.platform.PlatformServices
import com.mcosta.mybooks.platform.ScanOutcome
import com.mcosta.mybooks.ui.App
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Switching libraries rebuilds the whole app: nothing of the previous library stays on screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibrarySwitchUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val run = "switch-${System.nanoTime()}"
    private val files = context.filesDir.toOkioPath() / run
    private fun dbName(id: LibraryId) = "$run-${id.key}.db"
    private val accountA = LibraryId.Account("a")
    private val accountB = LibraryId.Account("b")

    private val container = AppContainer(
        openDatabase = { id -> createAndroidDatabase(context, dbName(id)) },
        deleteDatabase = { id -> context.deleteDatabase(dbName(id)) },
        libraryFiles = LibraryFiles(FileSystem.SYSTEM, files),
        httpEngine = MockEngine { respondError(HttpStatusCode.NotFound) },
        googleBooksApiKey = "",
        userAgent = "MyBooks test",
        releasesFileSystem = FileSystem.SYSTEM,
        releasesDir = files / "releases",
    ).also { runBlocking { it.start() } }

    private val services = PlatformServices(
        scanner = object : IsbnScanner { override suspend fun scan() = ScanOutcome.Cancelled },
        camera = object : CoverCamera { override suspend fun takePhoto(): ByteArray? = null },
        shrinker = object : PhotoShrinker { override fun shrink(photo: ByteArray, maxSide: Int) = photo },
        documents = object : DocumentPicker {
            override suspend fun save(suggestedName: String, mime: String, bytes: ByteArray) = false
            override suspend fun open(mimeTypes: List<String>): ByteArray? = null
        },
    )

    @After
    fun tearDown() {
        for (id in listOf(LibraryId.Local, accountA, accountB)) context.deleteDatabase(dbName(id))
        FileSystem.SYSTEM.deleteRecursively(files)
    }

    private fun waitFor(condition: () -> Boolean) =
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); condition() }

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun library(id: LibraryId, vararg titles: String) = runBlocking {
        container.libraries.activate(id)
        for (title in titles) container.repository.save(Book(title = title, authors = "Eça de Queirós", status = ReadingStatus.READING))
    }

    @Test
    fun switchShowsNoStaleBooks() {
        library(accountA, "A Cidade e as Serras", "O Mandarim")
        library(accountB, "Os Maias", "A Relíquia")
        compose.setContent { App(container, services) }
        waitFor { shown("Os Maias") }

        // A screen is still showing B: B stays open until it lets go.
        library(accountA)
        waitFor { shown("A Cidade e as Serras") }

        assertTrue(shown("O Mandarim"))
        for (title in listOf("Os Maias", "A Relíquia")) {
            assertTrue("$title still on screen", compose.onAllNodesWithText(title, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        }
    }
}
