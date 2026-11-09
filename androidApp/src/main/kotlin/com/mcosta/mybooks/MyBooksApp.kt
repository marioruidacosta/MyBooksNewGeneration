package com.mcosta.mybooks

import android.app.Application
import com.mcosta.mybooks.alerts.ReleaseAlertWorker
import com.mcosta.mybooks.social.SocialConfig
import com.mcosta.mybooks.widget.ReadingWidget

class MyBooksApp : Application(), ContainerOwner {
    override lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = androidAppContainer(
            this,
            BuildConfig.GOOGLE_BOOKS_API_KEY,
            BuildConfig.VERSION_NAME,
            SocialConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY),
        )
        // Kept scheduled while turned on (e.g. after a backup is restored on a new phone).
        if (container.releaseAlerts.enabled) ReleaseAlertWorker.schedule(this)
        ReadingWidget.follow(this, container)
        // Library sync retries as soon as the network is back.
        container.sync?.let { sync -> followConnectivity(this, sync::connectivityRestored) }
    }
}
