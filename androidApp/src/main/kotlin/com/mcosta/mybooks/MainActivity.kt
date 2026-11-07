package com.mcosta.mybooks

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.mcosta.mybooks.alerts.AndroidMonthlyAlerts
import com.mcosta.mybooks.platform.androidPlatformServices
import com.mcosta.mybooks.reader.AndroidEbookPlatform
import com.mcosta.mybooks.reader.ReaderActivity
import com.mcosta.mybooks.ui.App

class MainActivity : ComponentActivity() {
    /** A screen to open, from a notification; cleared once opened. */
    private val openRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MyBooksApp).container
        val services = androidPlatformServices(
            this,
            monthlyAlerts = AndroidMonthlyAlerts(this),
            ebooks = { results -> AndroidEbookPlatform(this, results, openReader = { ReaderActivity.start(this, it) }) },
        )
        if (savedInstanceState == null) openFrom(intent)
        setContent {
            App(container, services, openRoute = openRoute.value, onOpened = { openRoute.value = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    private fun openFrom(intent: Intent?) {
        // Any app can start this activity: only the screens the notification and the widget open are accepted.
        intent?.getStringExtra(OPEN_ROUTE)?.takeIf { OPENABLE.matches(it) }?.let { openRoute.value = it }
    }

    companion object {
        /** The screen to open (a route), set by the New in Portugal notification and the widget. */
        const val OPEN_ROUTE = "open-route"

        /** The routes another app's intent may open: New in Portugal, reading activity, a book, a shelf. */
        internal val OPENABLE = Regex("""releases|reading-activity|book/\d{1,18}|shelf/[A-Z_]{1,20}\?search=false""")
    }
}
