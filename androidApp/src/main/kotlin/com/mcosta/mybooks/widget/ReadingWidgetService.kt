package com.mcosta.mybooks.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.mcosta.mybooks.MainActivity
import com.mcosta.mybooks.MyBooksApp
import com.mcosta.mybooks.R
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.ui.Routes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Feeds the widget's stack: one card per book being read, from the active library. */
class ReadingWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val container = (applicationContext as MyBooksApp).container
        val covers = WidgetCovers(applicationContext.cacheDir, { container.coverStore })
        return ReadingCardsFactory(applicationContext, { runBlocking { container.readingSummaries.first().reading } }, covers)
    }
}

/**
 * The cards, rebuilt whenever the widget's data changes. Runs on a binder thread, where blocking (reading the library,
 * downloading a cover) is allowed.
 */
class ReadingCardsFactory(
    private val context: Context,
    private val books: () -> List<Book>,
    private val covers: WidgetCovers,
) : RemoteViewsService.RemoteViewsFactory {
    private var shown: List<Book> = emptyList()

    override fun onCreate() = Unit
    override fun onDestroy() = Unit

    override fun onDataSetChanged() {
        shown = try {
            books()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun getCount(): Int = shown.size

    override fun getViewAt(position: Int): RemoteViews {
        val book = shown.getOrNull(position) ?: return RemoteViews(context.packageName, R.layout.widget_card)
        return cardFor(context, book, covers.bitmapFor(book))
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = shown.getOrNull(position)?.id ?: position.toLong()
    override fun hasStableIds(): Boolean = true
}

/** One card: [cover], title, author and progress (hidden when unknown); a tap opens the book's page. */
internal fun cardFor(context: Context, book: Book, cover: Bitmap): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_card)
    views.setImageViewBitmap(R.id.card_cover, cover)
    views.setTextViewText(R.id.card_title, book.title)
    views.setTextViewText(R.id.card_author, book.authors)
    val percent = book.progressPercent
    if (percent != null) {
        views.setViewVisibility(R.id.card_progress_row, View.VISIBLE)
        views.setProgressBar(R.id.card_progress, 100, percent, false)
        views.setTextViewText(R.id.card_percent, "$percent%")
    } else {
        views.setViewVisibility(R.id.card_progress_row, View.GONE)
    }
    views.setOnClickFillInIntent(R.id.widget_card, cardFillIn(book))
    return views
}

/** What a card adds to the stack's tap: the book's page. */
internal fun cardFillIn(book: Book): Intent = Intent().putExtra(MainActivity.OPEN_ROUTE, Routes.book(book.id))
