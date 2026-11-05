package com.mcosta.mybooks.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import com.mcosta.mybooks.data.releases.ReleaseAlert
import com.mcosta.mybooks.ui.Routes
import java.util.concurrent.TimeUnit

/** Once a day (with a network), asks whether a newly finished New in Portugal month has books for you, and says so. */
class ReleaseAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MyBooksApp).container
        val alert = try {
            container.releaseAlerts.check()
        } catch (e: Exception) {
            return Result.retry()
        } ?: return Result.success()
        notify(applicationContext, alert)
        return Result.success()
    }

    companion object {
        private const val WORK = "release-alerts"
        private const val CHANNEL = "new-in-portugal"
        private const val NOTIFICATION = 1

        /** Starts the daily check (kept as it is when already scheduled). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReleaseAlertWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }

        /** The notification; tapping it opens New in Portugal. Nothing is shown without permission. */
        fun notify(context: Context, alert: ReleaseAlert) {
            val allowed = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            if (!allowed) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "New in Portugal", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Once a month: new books by your authors and publishers"
                },
            )
            val open = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.OPEN_ROUTE, Routes.RELEASES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val titles = (alert.byAuthors + alert.byPublishers).joinToString("\n") { b -> "${b.title} · ${b.authors.firstOrNull().orEmpty()}" }
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(alert.title)
                .setContentText(alert.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText("${alert.text}\n\n$titles"))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION, notification)
        }
    }
}
