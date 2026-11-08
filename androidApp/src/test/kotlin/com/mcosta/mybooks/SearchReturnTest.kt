package com.mcosta.mybooks

import android.content.Context
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.authors.AuthorBook
import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.db.createAndroidDatabase
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.data.releases.AllMonths
import com.mcosta.mybooks.ui.LocalCoverStore
import com.mcosta.mybooks.ui.library.LibraryScreen
import com.mcosta.mybooks.ui.library.LibraryViewModel
import com.mcosta.mybooks.ui.library.OutsideSearchViewModel
import com.mcosta.mybooks.ui.theme.MyBooksTheme
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Opening a result and coming back to Search shows the same results: no new search, no "Searching…". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchReturnTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "search-return-${System.nanoTime()}.db"
    private val db = createAndroidDatabase(context, dbName)
    private val repo = BookRepository(db.bookDao(), object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
    })

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    private fun waitFor(condition: () -> Boolean) =
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); condition() }

    @Test
    fun comingBackToSearchDoesNotSearchAgain() {
        var searches = 0
        val library = LibraryViewModel(repo, null, null)
        val outside = OutsideSearchViewModel(
            releases = { AllMonths(emptyList(), emptyList(), null) },
            online = { searches++; listOf(AuthorBook("Seeing", "José Saramago", 2006, null, null, null, null, null)) },
        )
        var onSearch by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalCoverStore provides CoverStore(FileSystem.SYSTEM, context.filesDir.toOkioPath() / "covers")) {
                MyBooksTheme {
                    if (onSearch) LibraryScreen(library, title = "Search", onBack = null, onOpenBook = {}, outside = outside)
                    else Text("A book's page")
                }
            }
        }
        compose.onNodeWithText("Search title, author or ISBN").performTextInput("lucidez")
        waitFor { searches == 1 && compose.onAllNodesWithTextCount("Seeing") == 1 }

        onSearch = false                                         // a result opened
        waitFor { compose.onAllNodesWithTextCount("A book's page") == 1 }
        onSearch = true                                          // and Back
        waitFor { compose.onAllNodesWithTextCount("Seeing") == 1 }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        compose.waitForIdle()

        assertEquals(1, searches)
        compose.onNodeWithText("Searching…").assertDoesNotExist()
        compose.onNodeWithText("Seeing").assertExists()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
