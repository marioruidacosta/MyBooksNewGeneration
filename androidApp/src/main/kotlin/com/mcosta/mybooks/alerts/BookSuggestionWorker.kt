package com.mcosta.mybooks.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mcosta.mybooks.MainActivity
import com.mcosta.mybooks.MyBooksApp
import com.mcosta.mybooks.R
import com.mcosta.mybooks.platform.ActivityResults
import com.mcosta.mybooks.platform.MonthlyAlerts
import com.mcosta.mybooks.social.SuggestionAlert
import com.mcosta.mybooks.ui.Routes
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Every hour or so (with a network), asks whether friends suggested new books, and says so. */
class BookSuggestionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val alerts = (applicationContext as MyBooksApp).container.suggestionAlerts ?: return Result.success()
        val alert = try {
            alerts.check()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Result.retry()
        } ?: return Result.success()
        // Signed out while this ran: not for whoever uses the phone now.
        if (!alerts.isCurrent(alert)) return Result.success()
        // Without permission nothing is shown, and it stays "not told": the badge still says so in the app.
        if (notify(applicationContext, alert)) alerts.notified(alert)
        return Result.success()
    }

    companion object {
        private const val WORK = "book-suggestions"
        private const val CHANNEL = "book-suggestions"
        private const val NOTIFICATION = 2

        /** Its own: PendingIntents that differ only in their extras would overwrite each other (the widget uses 0 to 2). */
        private const val TAP_REQUEST = 20

        /** Starts the regular check (kept as it is when already scheduled). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BookSuggestionWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }

        /** Takes it away: it can hold a friend's note, which isn't for the next account. */
        fun clearNotification(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION)

        /** The notification; tapping it opens Community. False when the app may not notify. */
        fun notify(context: Context, alert: SuggestionAlert): Boolean {
            val allowed = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            if (!allowed) return false
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Book suggestions", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When a friend suggests a book to you"
                },
            )
            val open = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.OPEN_ROUTE, Routes.ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val tap = PendingIntent.getActivity(context, TAP_REQUEST, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(alert.title)
                .setContentText(alert.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION, notification)
            return true
        }
    }
}

/** The suggestions check as Android work; turning it on asks for permission to notify (Android 13 and later). */
class AndroidSuggestionAlerts(private val activity: ComponentActivity) : MonthlyAlerts {
    private val results = ActivityResults(activity)

    override suspend fun enable(): Boolean {
        val needsAsking = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsking && !results.launch(ActivityResultContracts.RequestPermission(), Manifest.permission.POST_NOTIFICATIONS)) return false
        BookSuggestionWorker.schedule(activity.applicationContext)
        return true
    }

    override fun disable() = BookSuggestionWorker.cancel(activity.applicationContext)
}
