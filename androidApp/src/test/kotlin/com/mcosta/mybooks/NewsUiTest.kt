package com.mcosta.mybooks

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.data.news.NewsClient
import com.mcosta.mybooks.data.news.NewsRepository
import com.mcosta.mybooks.ui.news.NewsScreen
import com.mcosta.mybooks.ui.news.NewsViewModel
import com.mcosta.mybooks.ui.theme.MyBooksTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A news feed served by a mock engine, saved in a temporary folder; the screens' tests and screenshots use it. */
internal class NewsHarness(context: Context, var newsBody: String = fixtureNews()) {
    var offline = false
    var now: Instant = Instant.fromEpochMilliseconds(1794873827904)
    private val engine = MockEngine { request ->
        if (offline) throw java.io.IOException("no network")
        val body = when (request.url.encodedPath.substringAfterLast('/')) {
            "news.json" -> newsBody
            "events.json" -> File("../shared/src/commonTest/fixtures/news_events.json").readText()
            else -> ""
        }
        respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
    }
    val repo = NewsRepository(
        NewsClient(HttpClient(engine) { expectSuccess = false }, "https://pages.test/news/"),
        FileSystem.SYSTEM, (context.cacheDir.toOkioPath() / "news-${System.nanoTime()}"), { now },
    )

    fun viewModel() = NewsViewModel(repo, today = { LocalDate(2026, 11, 17) }, timeZone = TimeZone.UTC)

    companion object {
        fun fixtureNews(): String = File("../shared/src/commonTest/fixtures/news.json").readText()
    }
}

/** Records the links opened, or throws like a phone with no browser. */
internal class StubUriHandler(private val fails: Boolean = false) : UriHandler {
    val opened = mutableListOf<String>()
    override fun openUri(uri: String) {
        if (fails) throw IllegalStateException("no browser")
        opened += uri
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NewsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Robolectric's clock stands still while the test waits; move it on so delayed work on the main thread runs. */
    private fun waitFor(condition: () -> Boolean) =
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); condition() }

    private fun show(handler: UriHandler = StubUriHandler(), content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        // Inspection mode: pictures are not downloaded.
        CompositionLocalProvider(LocalUriHandler provides handler, LocalInspectionMode provides true) { MyBooksTheme { content() } }
    }

    private fun item(id: String, title: String, lang: String = "en", published: Long = 1794869340000) =
        """{"id":"$id","title":"$title","summary":"About $title","url":"https://news.test/$id","source":"s","lang":"$lang","published":$published}"""

    private fun feed(vararg items: String) = """{"generatedAt":1794873827904,"sources":[{"id":"s","name":"Test Source","lang":"en","ok":true}],"items":[${items.joinToString(",")}]}"""

    @Test
    fun aStoryTapOpensTheExactUrl() {
        val harness = NewsHarness(context)
        val vm = harness.viewModel()
        val handler = StubUriHandler()
        show(handler) { NewsScreen(vm, onBack = {}) }
        waitFor { vm.state.value.items.isNotEmpty() }
        val story = vm.state.value.items.first()
        compose.onNodeWithText(story.title).performClick()
        assertEquals(listOf(story.url), handler.opened)
    }

    @Test
    fun aHandlerThatThrowsShowsTheSnackbar() {
        val harness = NewsHarness(context)
        val vm = harness.viewModel()
        show(StubUriHandler(fails = true)) { NewsScreen(vm, onBack = {}) }
        waitFor { vm.state.value.items.isNotEmpty() }
        compose.onNodeWithText(vm.state.value.items.first().title).performClick()
        waitFor { compose.onAllNodesWithText("Couldn't open the link").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun anErrorSaysSoAndRetryLoads() {
        val harness = NewsHarness(context).apply { offline = true }
        val vm = harness.viewModel()
        show { NewsScreen(vm, onBack = {}) }
        waitFor { compose.onAllNodesWithText("Couldn't load the news. Try again later.").fetchSemanticsNodes().isNotEmpty() }
        harness.offline = false
        compose.onNodeWithText("Retry").performClick()
        waitFor { vm.state.value.items.isNotEmpty() }
        compose.onNodeWithText(vm.state.value.items.first().title).assertIsDisplayed()
    }

    @Test
    fun aFeedWithoutStoriesIsNotAnError() {
        val harness = NewsHarness(context, feed())
        val vm = harness.viewModel()
        show { NewsScreen(vm, onBack = {}) }
        waitFor { compose.onAllNodesWithText("No news yet. Check back later.").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Retry").assertCountEquals(0)
    }

    @Test
    fun theSavedCopyShowsItsDateWhenOffline() {
        val harness = NewsHarness(context)
        runBlocking { harness.repo.news() }
        harness.now += 31.minutes
        harness.offline = true
        val vm = harness.viewModel()
        show { NewsScreen(vm, onBack = {}) }
        waitFor { vm.state.value.savedAt != null }
        compose.onNodeWithText("Showing news from 17 Nov 2026").assertIsDisplayed()
        compose.onNodeWithText(vm.state.value.items.first().title).assertIsDisplayed()
    }

    @Test
    fun chipsFilterAndAnEmptyFilterSaysSo() {
        val harness = NewsHarness(context, feed(item("a", "English story one"), item("b", "English story two")))
        val vm = harness.viewModel()
        show { NewsScreen(vm, onBack = {}) }
        waitFor { vm.state.value.items.isNotEmpty() }
        compose.onNodeWithText("English story one").assertIsDisplayed()
        compose.onNodeWithText("Português").performClick()
        compose.onNodeWithText("No news in Português right now.").assertIsDisplayed()
        compose.onAllNodesWithText("English story one").assertCountEquals(0)
        compose.onNodeWithText("English").performClick()
        compose.onNodeWithText("English story one").assertIsDisplayed()
    }

    @Test
    fun duplicateIdsDoNotCrashTheScreen() {
        val harness = NewsHarness(context, feed(item("a", "Same story"), item("a", "Same story again"), item("b", "Other story")))
        val vm = harness.viewModel()
        show { NewsScreen(vm, onBack = {}) }
        waitFor { vm.state.value.items.isNotEmpty() }
        compose.onAllNodesWithText("Same story").assertCountEquals(1)
        compose.onNodeWithText("Other story").assertIsDisplayed()
    }

    @Test
    fun dayHeadingsAreHeadingsAndRowsAreButtons() {
        val harness = NewsHarness(context)
        val vm = harness.viewModel()
        show { NewsScreen(vm, onBack = {}) }
        waitFor { vm.state.value.items.isNotEmpty() }
        compose.onNodeWithText("Yesterday").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText(vm.state.value.items.first().title)
            .assert(SemanticsMatcher("is a button") { it.config.getOrNull(SemanticsProperties.Role)?.toString() == "Button" })
    }

    @Test
    fun theCalendarTabListsEventsFromThisMonth() {
        val harness = NewsHarness(context)
        val vm = harness.viewModel()
        show { NewsScreen(vm, onBack = {}) }
        waitFor { !vm.state.value.eventsLoading }
        compose.onNodeWithText("Book calendar").performClick()
        compose.onNodeWithText("Booker Prize").assertIsDisplayed()
        compose.onNodeWithText("Usually in November, London").assertIsDisplayed()
    }
}
