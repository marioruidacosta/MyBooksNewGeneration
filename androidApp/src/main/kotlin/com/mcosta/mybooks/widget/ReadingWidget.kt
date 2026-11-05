package com.mcosta.mybooks.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.mcosta.mybooks.AppContainer
import com.mcosta.mybooks.MainActivity
import com.mcosta.mybooks.MyBooksApp
import com.mcosta.mybooks.R
import com.mcosta.mybooks.domain.ReadingSummary
import com.mcosta.mybooks.ui.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The home-screen widget: the book being read with its progress, and this year's challenge. */
class ReadingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        val container = (context.applicationContext as MyBooksApp).container
        CoroutineScope(Dispatchers.Default).launch {
            try {
                show(context, summary(container))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private suspend fun summary(container: AppContainer): ReadingSummary = container.readingSummaries.first()

        /** Keeps the widgets up to date while the app runs: the library changes only in the app. */
        fun follow(context: Context, container: AppContainer) {
            container.applicationScope.launch {
                container.readingSummaries
                    .distinctUntilChanged()
                    .collect { show(context, it) }
            }
        }

        private fun show(context: Context, summary: ReadingSummary) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ReadingWidget::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, views(context, summary))
        }

        fun views(context: Context, summary: ReadingSummary): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_reading)
            val book = summary.current
            if (book == null) {
                views.setTextViewText(R.id.widget_label, "Reading")
                views.setTextViewText(R.id.widget_title, "Nothing on the go")
                views.setTextViewText(R.id.widget_author, "Pick your next book from To read")
                views.setViewVisibility(R.id.widget_progress_row, View.GONE)
                views.setOnClickPendingIntent(R.id.widget_book, open(context, Routes.shelf(com.mcosta.mybooks.domain.ReadingStatus.TO_READ), 1))
            } else {
                views.setTextViewText(R.id.widget_label, if (summary.alsoReading > 0) "Reading · and ${summary.alsoReading} more" else "Reading")
                views.setTextViewText(R.id.widget_title, book.title)
                views.setTextViewText(R.id.widget_author, book.authors)
                val percent = book.progressPercent
                if (percent != null) {
                    views.setViewVisibility(R.id.widget_progress_row, View.VISIBLE)
                    views.setProgressBar(R.id.widget_progress, 100, percent, false)
                    views.setTextViewText(R.id.widget_percent, "$percent%")
                } else {
                    views.setViewVisibility(R.id.widget_progress_row, View.GONE)
                }
                views.setOnClickPendingIntent(R.id.widget_book, open(context, Routes.book(book.id), 1))
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
