package com.mcosta.mybooks

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.alerts.ReleaseAlertWorker
import com.mcosta.mybooks.data.releases.ReleaseAlert
import com.mcosta.mybooks.data.releases.ReleaseBook
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ChallengeProgress
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.domain.ReadingSummary
import com.mcosta.mybooks.ui.Routes
import com.mcosta.mybooks.widget.ReadingWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The widget's content, the notification, and which screens other apps may open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoundFourAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun onlyTheNotificationsAndWidgetsScreensCanBeOpenedFromOutside() {
        for (route in listOf(Routes.RELEASES, Routes.ACTIVITY, Routes.READING_ACTIVITY, Routes.book(42), Routes.shelf(ReadingStatus.TO_READ))) {
            assertTrue(route, MainActivity.OPENABLE.matches(route))
        }
        for (route in listOf("edit/42", Routes.SETTINGS, "book/42/x", "book/", Routes.shelf(ReadingStatus.TO_READ, search = true), "account")) {
            assertFalse(route, MainActivity.OPENABLE.matches(route))
        }
    }

    private fun widget(summary: ReadingSummary): View =
        ReadingWidget.views(context, summary).apply(context, FrameLayout(context))

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test
    fun widgetShowsHowManyBooksAndTheChallenge() {
        val book = Book(id = 7, title = "Princípio, meio, fim", authors = "Valeria Luiselli", status = ReadingStatus.READING, progressPercent = 26)
        val view = widget(ReadingSummary(listOf(book, book.copy(id = book.id + 1, title = "Other")), challenge = ChallengeProgress(2026, 25, 5)))
        assertEquals("Reading · 2 books", view.text(R.id.widget_label))
        assertEquals("2026 challenge: 5 of 25 books", view.text(R.id.widget_challenge_text))
        assertEquals(20, view.findViewById<ProgressBar>(R.id.widget_challenge_progress).progress)
    }

    @Test
    fun widgetWithOneBookSaysReading() {
        val book = Book(id = 7, title = "Stoner", status = ReadingStatus.READING)
        assertEquals("Reading", widget(ReadingSummary(listOf(book), null)).text(R.id.widget_label))
    }

    @Test
    fun nothingBeingReadShowsTheToReadCard() {
        val view = widget(ReadingSummary(emptyList(), null))
        assertEquals("Nothing on the go", view.text(R.id.widget_empty_title))
        assertEquals("Pick your next book from To read", view.text(R.id.widget_empty_hint))
        assertEquals(View.GONE, view.findViewById<View>(R.id.widget_challenge).visibility)
    }

    private val alert = ReleaseAlert(
        "2026-10",
        byAuthors = listOf(ReleaseBook("1", "9789897836848", "Os túmulos de Atuan", listOf("Ursula K. Le Guin"))),
        byPublishers = emptyList(),
        authors = listOf("Ursula K. Le Guin"),
        publishers = emptyList(),
    )

    @Test
    fun theNotificationSaysWhatsNewAndOpensNewInPortugal() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ReleaseAlertWorker.notify(context, alert)
        val manager = context.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("New in Portugal · October 2026", shadowOf(posted).contentTitle.toString())
        assertEquals("1 by Ursula K. Le Guin", shadowOf(posted).contentText.toString())
        val opens = shadowOf(posted.contentIntent).savedIntent
        assertEquals(Routes.RELEASES, opens.getStringExtra(MainActivity.OPEN_ROUTE))
    }

    @Test
    fun theNotificationKeepsItsRouteWhenTheWidgetUpdates() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ReleaseAlertWorker.notify(context, alert)
        val manager = context.getSystemService(NotificationManager::class.java)
        val tap = shadowOf(manager).allNotifications.single().contentIntent
        // Another link on the same request code would replace its route (the widget uses 0 to 2).
        android.app.PendingIntent.getActivity(
            context, 0,
            android.content.Intent(context, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.OPEN_ROUTE, Routes.READING_ACTIVITY),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        assertEquals(Routes.RELEASES, shadowOf(tap).savedIntent.getStringExtra(MainActivity.OPEN_ROUTE))
    }

    @Test
    fun noNotificationWithoutPermission() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ReleaseAlertWorker.notify(context, alert)
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }
}
