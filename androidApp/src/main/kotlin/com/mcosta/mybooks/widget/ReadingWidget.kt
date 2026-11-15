package com.mcosta.mybooks.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import com.mcosta.mybooks.AppContainer
import com.mcosta.mybooks.MainActivity
import com.mcosta.mybooks.MyBooksApp
import com.mcosta.mybooks.R
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.domain.ReadingSummary
import com.mcosta.mybooks.ui.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The home-screen widget: a shelf row with the covers of the books being read, and this year's challenge. */
class ReadingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        val container = (context.applicationContext as MyBooksApp).container
        CoroutineScope(Dispatchers.Default).launch {
            try {
                show(context, container, summary(container))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Covers shown side by side; with more books, the last spot becomes "+N". */
        private const val SPOTS = 4
        private val COVERS = listOf(R.id.shelf_cover_0, R.id.shelf_cover_1, R.id.shelf_cover_2, R.id.shelf_cover_3)
        private val SPOT_VIEWS = listOf(R.id.shelf_spot_0, R.id.shelf_spot_1, R.id.shelf_spot_2, R.id.shelf_spot_3)
        private val BARS = listOf(R.id.shelf_progress_0, R.id.shelf_progress_1, R.id.shelf_progress_2, R.id.shelf_progress_3)

        private suspend fun summary(container: AppContainer): ReadingSummary = container.readingSummaries.first()

        /** Keeps the widgets up to date while the app runs: the library changes only in the app. */
        fun follow(context: Context, container: AppContainer) {
            container.applicationScope.launch {
                container.readingSummaries
                    .distinctUntilChanged()
                    .collect { show(context, container, it) }
            }
        }

        private suspend fun show(context: Context, container: AppContainer, summary: ReadingSummary) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ReadingWidget::class.java))
            if (ids.isEmpty()) return
            // Covers may be downloaded (once, then kept): off the main thread.
            val covers = WidgetCovers(context.cacheDir, { container.coverStore })
            val views = withContext(Dispatchers.IO) { views(context, summary, covers::bitmapFor) }
            manager.updateAppWidget(ids, views)
        }

        /** The widget for [summary]; [cover] draws a book's cover (blocking: may download it). */
        fun views(
            context: Context,
            summary: ReadingSummary,
            cover: (Book) -> Bitmap = { generatedCoverBitmap(it.title, it.authors) },
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_reading)
            val books = summary.reading
            views.setTextViewText(R.id.widget_label, if (books.size > 1) "Reading · ${books.size} books" else "Reading")
            if (books.isEmpty()) {
                views.setViewVisibility(R.id.shelf_row, View.GONE)
                views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                views.setOnClickPendingIntent(R.id.widget_empty, open(context, Routes.shelf(ReadingStatus.TO_READ), 1))
            } else {
                views.setViewVisibility(R.id.shelf_row, View.VISIBLE)
                views.setViewVisibility(R.id.widget_empty, View.GONE)
                // Four fit; with more, three covers and "+N" for the rest.
                val shown = if (books.size > SPOTS) books.take(SPOTS - 1) else books
                for (i in 0 until SPOTS) {
                    val book = shown.getOrNull(i)
                    if (book == null) {
                        views.setViewVisibility(SPOT_VIEWS[i], View.GONE)
                        continue
                    }
                    views.setViewVisibility(SPOT_VIEWS[i], View.VISIBLE)
                    views.setImageViewBitmap(COVERS[i], cover(book))
                    views.setContentDescription(COVERS[i], book.title)
                    val percent = book.progressPercent
                    if (percent != null) {
                        views.setViewVisibility(BARS[i], View.VISIBLE)
                        views.setProgressBar(BARS[i], 100, percent, false)
                    } else {
                        views.setViewVisibility(BARS[i], View.INVISIBLE)
                    }
                    views.setOnClickPendingIntent(SPOT_VIEWS[i], open(context, Routes.book(book.id), 10 + i))
                }
                if (books.size > SPOTS) {
                    views.setViewVisibility(R.id.shelf_more, View.VISIBLE)
                    views.setTextViewText(R.id.shelf_more, "+${books.size - shown.size}")
                    views.setOnClickPendingIntent(R.id.shelf_more, open(context, Routes.shelf(ReadingStatus.READING), 3))
                } else {
                    views.setViewVisibility(R.id.shelf_more, View.GONE)
                }
            }
            val challenge = summary.challenge
            if (challenge == null) {
                views.setViewVisibility(R.id.widget_challenge, View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_challenge, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_challenge_text,
                    if (challenge.complete) "${challenge.year} challenge done: ${challenge.read} of ${challenge.goal}"
                    else "${challenge.year} challenge: ${challenge.read} of ${challenge.goal} books",
                )
                views.setProgressBar(R.id.widget_challenge_progress, 100, challenge.percent, false)
                views.setOnClickPendingIntent(R.id.widget_challenge, open(context, Routes.READING_ACTIVITY, 2))
            }
            views.setOnClickPendingIntent(R.id.widget_root, open(context, null, 0))
            return views
        }

        private fun open(context: Context, route: String?, request: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (route != null) intent.putExtra(MainActivity.OPEN_ROUTE, route)
            return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
