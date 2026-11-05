package com.mcosta.mybooks

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.PinnedTags
import com.mcosta.mybooks.data.db.createAndroidDatabase
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.BookFilter
import com.mcosta.mybooks.ui.library.LibraryScreen
import com.mcosta.mybooks.ui.library.LibraryViewModel
import com.mcosta.mybooks.ui.theme.MyBooksTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The pin on a tag's list says what it just did. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PinFeedbackTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "pin-${System.nanoTime()}.db"
    private val db = createAndroidDatabase(context, dbName)
    private val repo = BookRepository(db.bookDao(), object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
    })

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    /** Robolectric's clock stands still while the test waits; move it on so delayed work on the main thread runs. */
    private fun waitFor(condition: () -> Boolean) =
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); condition() }

    private fun shows(text: String) = compose.onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun pinningSaysShownOnHomeAndUnpinningSaysRemoved() {
        runBlocking { repo.save(Book(title = "Dune", tags = listOf("favourites"))) }
        val pinned = PinnedTags(null)
        val vm = LibraryViewModel(repo, filter = BookFilter.Tag("favourites"), pinnedTags = pinned)
        compose.setContent { MyBooksTheme { LibraryScreen(vm, title = "favourites", onBack = {}, onOpenBook = {}) } }
        waitFor { vm.state.value.books.isNotEmpty() }

        compose.onNodeWithContentDescription("Show on Home").performClick()
        waitFor { shows("Shown on Home") }
        assertEquals(listOf("favourites"), pinned.tags.value)
        compose.onNodeWithText("Shown on Home").assertExists()

        compose.onNodeWithContentDescription("Remove from Home").performClick()
        waitFor { shows("Removed from Home") }
        assertEquals(listOf<String>(), pinned.tags.value)
    }
}
