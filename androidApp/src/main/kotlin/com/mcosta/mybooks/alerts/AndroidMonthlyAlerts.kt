package com.mcosta.mybooks.alerts

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.mcosta.mybooks.platform.ActivityResults
import com.mcosta.mybooks.platform.MonthlyAlerts

/** The monthly check as Android work; turning it on asks for permission to notify (Android 13 and later). */
class AndroidMonthlyAlerts(private val activity: ComponentActivity) : MonthlyAlerts {
    private val results = ActivityResults(activity)

    override suspend fun enable(): Boolean {
        val needsAsking = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsking && !results.launch(ActivityResultContracts.RequestPermission(), Manifest.permission.POST_NOTIFICATIONS)) return false
        ReleaseAlertWorker.schedule(activity.applicationContext)
        return true
    }

    override fun disable() = ReleaseAlertWorker.cancel(activity.applicationContext)
}
