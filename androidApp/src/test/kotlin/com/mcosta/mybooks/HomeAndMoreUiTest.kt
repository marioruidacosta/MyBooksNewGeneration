package com.mcosta.mybooks

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.ChallengeRepository
import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.db.createAndroidDatabase
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.ui.LocalCoverStore
import com.mcosta.mybooks.ui.home.HomeScreen
import com.mcosta.mybooks.ui.home.HomeViewModel
import com.mcosta.mybooks.ui.settings.MoreScreen
import com.mcosta.mybooks.ui.theme.MyBooksTheme
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Home opens with the reading activity, as the website does; Book news and Settings live under More. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeAndMoreUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "home-more-${System.nanoTime()}.db"
    private val db = createAndroidDatabase(context, dbName)

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun theReadingActivityComesBeforeTheBooksBeingRead() {
        val repo = BookRepository(db.bookDao(), object : BookLookupService {
            override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
        })
        runBlocking { repo.save(Book(title = "Stoner", authors = "John Williams", status = ReadingStatus.READING)) }
        val vm = HomeViewModel(repo, ChallengeRepository(db.challengeDao()))
        compose.setContent {
            CompositionLocalProvider(LocalCoverStore provides CoverStore(FileSystem.SYSTEM, context.filesDir.toOkioPath() / "covers")) {
                MyBooksTheme {
                    HomeScreen(vm, SnackbarHostState(), onOpenBook = {}, onOpenShelf = {}, onOpenReadingActivity = {}, onAddManually = {}, onScan = {})
                }
            }
        }
        compose.waitUntil(10_000) { shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); vm.state.value.loaded }
        compose.waitForIdle()

        val activity = compose.onNodeWithText("Your reading activity").getUnclippedBoundsInRoot().top
        val reading = compose.onNodeWithText("Reading · 1").getUnclippedBoundsInRoot().top
        assertTrue("activity at $activity, Reading at $reading", activity < reading)
    }

    @Test
    fun moreOpensBookNewsAndSettings() {
        val opened = mutableListOf<String>()
        compose.setContent {
            MyBooksTheme { MoreScreen(onOpenNews = { opened += "news" }, onOpenSettings = { opened += "settings" }) }
        }
        compose.onNodeWithText("Book news").performClick()
        compose.onNodeWithText("Settings").performClick()
        assertEquals(listOf("news", "settings"), opened)
    }
}
