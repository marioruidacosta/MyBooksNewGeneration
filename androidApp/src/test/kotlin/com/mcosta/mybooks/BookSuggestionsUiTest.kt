package com.mcosta.mybooks

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.alerts.BookSuggestionWorker
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.social.SuggestionAlert
import com.mcosta.mybooks.social.model.BookSuggestion
import com.mcosta.mybooks.social.model.FeedBook
import com.mcosta.mybooks.social.model.Person
import com.mcosta.mybooks.ui.Routes
import com.mcosta.mybooks.ui.social.SuggestBookContent
import com.mcosta.mybooks.ui.social.SuggestedToYou
import com.mcosta.mybooks.ui.theme.MyBooksTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.time.Instant

/** Suggesting a book to friends, and what the person who receives it can do. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookSuggestionsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val ana = Person("a", "ana", "Ana", null)
    private val rui = Person("r", "rui", "Rui", null)
    private val book = FeedBook("s", "9780141439587", "Stoner", "John Williams", null)
    private val suggestion = BookSuggestion(7, rui, book, "You'd love this", Instant.fromEpochMilliseconds(1_800_000_000_000), false)

    @Test
    fun sendGoesToTheChosenFriendsWithTheNote() {
        var sentTo: List<String>? = null
        var sentNote: String? = null
        var done: String? = null
        compose.setContent {
            MyBooksTheme {
                SuggestBookContent(
                    book,
                    loadFriends = { listOf(rui, ana) },
                    send = { friends, note -> sentTo = friends; sentNote = note; friends.size },
                    onDone = { done = it },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithText("Ana").performClick()
        compose.onNodeWithText("Send").assertIsEnabled()
        compose.onNodeWithText("Note (optional)").performTextInput("  Read it  ")
        compose.onNodeWithText("Send").performClick()
        compose.waitForIdle()
        assertEquals(listOf("a"), sentTo)
        assertEquals("Read it", sentNote)
        assertEquals("Suggested to Ana", done)
    }

    @Test
    fun aFailedSendStaysOpenWithTheChoice() {
        var done: String? = null
        compose.setContent {
            MyBooksTheme {
                SuggestBookContent(
                    book,
                    loadFriends = { listOf(ana) },
                    send = { _, _ -> throw IllegalStateException("offline") },
                    onDone = { done = it },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Ana").performClick()
        compose.onNodeWithText("Send").performClick()
        compose.waitForIdle()
        assertEquals(null, done)
        compose.onNodeWithText("Send").assertIsEnabled()
    }

    @Test
    fun withoutFriendsItSaysHowToGetSome() {
        compose.setContent {
            MyBooksTheme { SuggestBookContent(book, loadFriends = { emptyList() }, send = { _, _ -> 0 }, onDone = {}) }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Add friends in Community to suggest books to them.").assertExists()
    }

    @Test
    fun theNotificationKeepsItsOwnTapWhenTheWidgetUpdates() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(BookSuggestionWorker.notify(app, SuggestionAlert("Rui suggested a book", "Stoner", "me", 1)))
        val tap = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.single().contentIntent
        // What the widget's challenge link does.
        PendingIntent.getActivity(
            app, 2,
            Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.OPEN_ROUTE, Routes.READING_ACTIVITY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        assertEquals(Routes.ACTIVITY, shadowOf(tap).savedIntent.getStringExtra(MainActivity.OPEN_ROUTE))
    }

    @Test
    fun aSuggestionOffersEveryAction() {
        val done = mutableListOf<String>()
        compose.setContent {
            MyBooksTheme {
                SuggestedToYou(
                    listOf(suggestion),
                    onPerson = { done += "person:$it" },
                    onBook = { done += "book" },
                    onAccept = { _, status -> done += status.name },
                    onDismiss = { done += "dismiss" },
                    onReport = { done += "report" },
                )
            }
        }
        compose.onNodeWithText("“You'd love this”").assertExists()
        compose.onNodeWithText("Rui suggested").performClick()
        compose.onNodeWithText("Want to read").performClick()
        compose.onNodeWithText("To buy").performClick()
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithContentDescription("More about this suggestion").performClick()
        compose.onNodeWithText("Report").performClick()
        assertEquals(listOf("person:r", ReadingStatus.TO_READ.name, ReadingStatus.WISHLIST.name, "dismiss", "report"), done)
    }
}
