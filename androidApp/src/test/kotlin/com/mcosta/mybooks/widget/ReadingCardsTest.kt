package com.mcosta.mybooks.widget

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.MainActivity
import com.mcosta.mybooks.R
import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.ui.Routes
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ReadingCardsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val covers by lazy {
        WidgetCovers(temp.newFolder("cache"), { CoverStore(FileSystem.SYSTEM, temp.newFolder("covers").toOkioPath()) }) { null }
    }

    private fun reading(id: Long, title: String, percent: Int? = null) =
        Book(id = id, title = title, authors = "Author $id", status = ReadingStatus.READING, progressPercent = percent)

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test
    fun everyBookBeingReadGetsACardInOrder() {
        val books = listOf(reading(4, "Four"), reading(9, "Nine"), reading(2, "Two"), reading(5, "Five"))
        val factory = ReadingCardsFactory(context, { books }, covers)
        factory.onDataSetChanged()
        assertEquals(4, factory.count)
        assertEquals(listOf("Four", "Nine", "Two", "Five"), (0 until 4).map { factory.getViewAt(it).apply(context, FrameLayout(context)).text(R.id.card_title) })
        assertEquals(listOf(4L, 9L, 2L, 5L), (0 until 4).map { factory.getItemId(it) })
    }

    @Test
    fun aCardShowsTitleAuthorProgressAndOpensTheBook() {
        val book = reading(7, "Princípio, meio, fim", percent = 26)
        val views = cardFor(context, book, generatedCoverBitmap(book.title, book.authors))
        val card = views.apply(context, FrameLayout(context))
        assertEquals("Princípio, meio, fim", card.text(R.id.card_title))
        assertEquals("Author 7", card.text(R.id.card_author))
        assertEquals("26%", card.text(R.id.card_percent))
        assertEquals(View.VISIBLE, card.findViewById<View>(R.id.card_progress_row).visibility)
        assertEquals(Routes.book(7), cardFillIn(book).getStringExtra(MainActivity.OPEN_ROUTE))
    }

    /** At the widget's minimum height (and in a widget still sized 4×2) the stack is short: the cover must fit it. */
    @Test
    fun aCardFitsAShortStack() {
        val book = reading(3, "Stoner", percent = 40)
        val card = cardFor(context, book, generatedCoverBitmap(book.title, book.authors)).apply(context, FrameLayout(context))
        val density = context.resources.displayMetrics.density
        val width = (300 * density).toInt()
        val height = (80 * density).toInt()
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        card.layout(0, 0, width, height)
        val cover = card.findViewById<View>(R.id.card_cover)
        assertTrue("cover ${cover.top}..${cover.bottom} in a card of $height", cover.top >= 0 && cover.bottom <= height)
        assertTrue(cover.height > 0)
    }

    @Test
    fun aCardWithoutProgressHidesTheBar() {
        val book = reading(3, "Stoner")
        val card = cardFor(context, book, generatedCoverBitmap(book.title, book.authors)).apply(context, FrameLayout(context))
        assertEquals(View.GONE, card.findViewById<View>(R.id.card_progress_row).visibility)
    }
}
