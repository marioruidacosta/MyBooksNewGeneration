package com.mcosta.mybooks.widget

import android.app.PendingIntent
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.graphics.Color
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.MainActivity
import com.mcosta.mybooks.R
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.domain.ReadingSummary
import com.mcosta.mybooks.ui.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The widget's shelf row: up to four covers with their progress; a "+N" spot when more are being read. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ShelfWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val spots = listOf(R.id.shelf_spot_0, R.id.shelf_spot_1, R.id.shelf_spot_2, R.id.shelf_spot_3)
    private val covers = listOf(R.id.shelf_cover_0, R.id.shelf_cover_1, R.id.shelf_cover_2, R.id.shelf_cover_3)

    private fun reading(n: Int, percent: Int? = 10 * n) =
        (1..n).map { Book(id = it.toLong(), title = "Book $it", authors = "A", status = ReadingStatus.READING, progressPercent = percent) }

    private fun widget(books: List<Book>): View =
        ReadingWidget.views(context, ReadingSummary(books, null)) { generatedCoverBitmap(it.title, it.authors) }
            .apply(context, FrameLayout(context))

    private fun View.visible(id: Int) = findViewById<View>(id).visibility == View.VISIBLE

    @Test
    fun upToFourBooksShowTheirCovers() {
        val view = widget(reading(3))
        assertEquals(listOf(true, true, true, false), spots.map { view.visible(it) })
        assertEquals(false, view.visible(R.id.shelf_more))
        assertEquals(false, view.visible(R.id.widget_empty))
    }

    @Test
    fun moreThanFourShowsThreeCoversAndACount() {
        val view = widget(reading(6))
        assertEquals(listOf(true, true, true, false), spots.map { view.visible(it) })
        assertEquals(true, view.visible(R.id.shelf_more))
        assertEquals("+3", view.findViewById<TextView>(R.id.shelf_more).text.toString())
    }

    @Test
    fun exactlyFourShowsFourCovers() {
        val view = widget(reading(4))
        assertEquals(listOf(true, true, true, true), spots.map { view.visible(it) })
        assertEquals(false, view.visible(R.id.shelf_more))
    }

    /**
     * The cover and its progress bar are one picture: the bar is exactly as wide as the cover and sits under it, whatever
     * the widget's size (a separate bar spanned the whole spot while the cover sat at its right).
     */
    @Test
    fun theProgressBarIsAsWideAsTheCover() {
        val cover = generatedCoverBitmap("Stoner", "John Williams")
        val spot = shelfSpotBitmap(cover, percent = 50, track = Color.LTGRAY, accent = Color.BLUE)
        assertEquals(cover.width, spot.width)
        assertTrue(spot.height > cover.height)
        val barY = spot.height - 3
        assertEquals(Color.BLUE, spot.getPixel(spot.width / 4, barY))       // half filled, from the cover's left
        assertEquals(Color.LTGRAY, spot.getPixel(spot.width * 3 / 4, barY)) // the track runs to the cover's right edge
    }

    /** Without progress the bar's place stays empty, so every cover in the row sits at the same height. */
    @Test
    fun noProgressLeavesTheBarEmptyButTheCoverLevel() {
        val cover = generatedCoverBitmap("Stoner", "John Williams")
        val withBar = shelfSpotBitmap(cover, percent = 50, track = Color.LTGRAY, accent = Color.BLUE)
        val without = shelfSpotBitmap(cover, percent = null, track = Color.LTGRAY, accent = Color.BLUE)
        assertEquals(withBar.height, without.height)
        assertEquals(Color.TRANSPARENT, without.getPixel(without.width / 2, without.height - 3))
    }

    @Test
    fun aCoverSaysItsTitleAndProgress() {
        val view = widget(listOf(reading(1, percent = 26).single(), reading(2, percent = null)[1]))
        assertEquals("Book 1, 26% read", view.findViewById<View>(covers[0]).contentDescription)
        assertEquals("Book 2", view.findViewById<View>(covers[1]).contentDescription)
    }

    @Test
    fun aCoverOpensItsBook() {
        val view = widget(reading(2))
        view.findViewById<View>(R.id.shelf_spot_1).performClick()
        val started = shadowOf(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity
        assertEquals(Routes.book(2), started.getStringExtra(MainActivity.OPEN_ROUTE))
    }

    @Test
    fun nothingBeingReadShowsTheToReadCardAndNoCovers() {
        val view = widget(emptyList())
        assertEquals(true, view.visible(R.id.widget_empty))
        assertEquals(false, view.visible(R.id.shelf_row))
    }
}
