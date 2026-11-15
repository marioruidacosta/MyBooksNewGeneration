package com.mcosta.mybooks

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
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
import com.mcosta.mybooks.ui.SharedCovers
import com.mcosta.mybooks.ui.ShareKey
import com.mcosta.mybooks.ui.components.BookCover
import com.mcosta.mybooks.ui.home.HomeScreen
import com.mcosta.mybooks.ui.home.HomeViewModel
import com.mcosta.mybooks.ui.screen
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
import org.robolectric.annotation.Config

/** The cover growing into the book page: navigation keeps working, and each screen shares a book's cover only once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoverTransitionUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "cover-transition-${System.nanoTime()}.db"
    private val db = createAndroidDatabase(context, dbName)

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    /** A list and a book page joined by a shared cover, as in the app; Back during the move still lands on the list. */
    @Test
    fun openingABookAndGoingBackStillWorks() {
        var navigator: androidx.navigation.NavHostController? = null
        compose.setContent {
            CompositionLocalProvider(LocalCoverStore provides CoverStore(FileSystem.SYSTEM, context.filesDir.toOkioPath() / "covers")) {
                MyBooksTheme {
                    val nav = rememberNavController().also { navigator = it }
                    SharedCovers {
                        androidx.navigation.compose.NavHost(nav, startDestination = "list") {
                            screen("list") {
                                Column(Modifier.clickable { nav.navigate("book") }) {
                                    BookCover(null, Modifier.size(40.dp, 60.dp), title = "Stoner", shareKey = "book-1")
                                    Text("The list")
                                }
                            }
                            screen("book") {
                                Column {
                                    BookCover(null, Modifier.size(150.dp, 225.dp), title = "Stoner", shareKey = "book-1")
                                    Text("The book page")
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("The list").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("The book page").assertExists()
        // Open it again and go back while the cover is still moving.
        compose.runOnUiThread { navigator!!.popBackStack() }
        compose.waitForIdle()
        compose.onNodeWithText("The list").performClick()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { navigator!!.popBackStack() }
        compose.mainClock.advanceTimeBy(100)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("The list").assertExists()
    }

    /** Home shows a book being read in the Reading row and maybe in a shelf: only one of them may share its cover. */
    @Test
    fun homeUsesOneShareKeyPerBook() {
        val repo = BookRepository(db.bookDao(), object : BookLookupService {
            override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
        })
        val challenges = ChallengeRepository(db.challengeDao())
        runBlocking {
            repo.save(Book(title = "Stoner", authors = "John Williams", status = ReadingStatus.READING, tags = listOf("favourites")))
            repo.save(Book(title = "Solaris", authors = "Stanisław Lem", status = ReadingStatus.READ, tags = listOf("favourites")))
        }
        val pinned = com.mcosta.mybooks.data.PinnedTags(null).apply { setPinned("favourites", true) }
        val vm = HomeViewModel(repo, challenges, pinnedTags = pinned)
        compose.setContent {
            CompositionLocalProvider(LocalCoverStore provides CoverStore(FileSystem.SYSTEM, context.filesDir.toOkioPath() / "covers")) {
                MyBooksTheme {
                    HomeScreen(vm, SnackbarHostState(), onOpenBook = {}, onOpenShelf = {}, onOpenReadingActivity = {}, onAddManually = {}, onScan = {})
                }
            }
        }
        // Robolectric's clock stands still while the test waits; move it on so Home's loading runs (as ScreenshotTest does).
        compose.waitUntil(10_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50)); vm.state.value.loaded }
        compose.waitForIdle()
        val keys = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(ShareKey), useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.config[ShareKey] }
        assertTrue("Home shares the Reading row's covers: $keys", keys.isNotEmpty())
        assertEquals(keys.distinct(), keys)
    }
}
