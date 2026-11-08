package com.mcosta.mybooks

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.db.createAndroidDatabase
import com.mcosta.mybooks.platform.CoverCamera
import com.mcosta.mybooks.platform.DocumentPicker
import com.mcosta.mybooks.platform.IsbnScanner
import com.mcosta.mybooks.data.covers.PhotoShrinker
import com.mcosta.mybooks.platform.PlatformServices
import com.mcosta.mybooks.platform.ScanOutcome
import com.mcosta.mybooks.ui.App
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** BookNerd in the whole app: Send on the keyboard sends, and the conversation is still there after leaving. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookNerdConversationTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "booknerd-${System.nanoTime()}.db"
    private val files = context.filesDir.toOkioPath() / dbName.removeSuffix(".db")
    private val container = AppContainer(
        database = createAndroidDatabase(context, dbName),
        httpEngine = MockEngine { respondError(HttpStatusCode.NotFound) },
        coverStore = CoverStore(FileSystem.SYSTEM, files / "covers"),
        googleBooksApiKey = "",
        userAgent = "MyBooks test",
        releasesFileSystem = FileSystem.SYSTEM,
        releasesDir = files / "releases",
    )
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
        context.deleteDatabase(dbName)
        FileSystem.SYSTEM.deleteRecursively(files)
    }

    private fun waitFor(condition: () -> Boolean) =
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); condition() }

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun keyboardSendsAndConversationSurvivesLeaving() {
        compose.setContent { App(container, services) }
        waitFor { compose.onAllNodesWithContentDescription("BookNerd").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("BookNerd").performClick()
        waitFor { shown("Ask BookNerd") }

        compose.onNodeWithText("Ask BookNerd…").performTextInput("hello there")
        compose.onNodeWithText("hello there").performImeAction()
        waitFor { shown("I can help with three things:") }

        compose.onNodeWithContentDescription("Back").performClick()
        waitFor { compose.onAllNodesWithContentDescription("BookNerd").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("BookNerd").performClick()
        waitFor { shown("Ask BookNerd") }

        compose.onNodeWithText("hello there").assertExists()
        compose.onNodeWithText("I can help with three things:").assertExists()
    }
}
