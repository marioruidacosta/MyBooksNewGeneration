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
import com.mcosta.mybooks.reader.IncomingFiles
import com.mcosta.mybooks.ui.Routes
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
        // An EPUB, PDF or .acsm opened or shared with MyBooks: copied now (the other app's permission ends soon),
        // then "Add an ebook".
        val book = intent?.let(::bookUri) ?: return
        val container = (application as MyBooksApp).container
        lifecycleScope.launch {
            try {
                val file = IncomingFiles.copy(this@MainActivity, book)
                openRoute.value = Routes.addEbook(container.pendingFiles.put(file))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Couldn't open the file: ${e.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        /** The screen to open (a route), set by the New in Portugal notification and the widget. */
        const val OPEN_ROUTE = "open-route"

        /** The routes another app's intent may open: New in Portugal, reading activity, a book, a shelf, adding an ebook. */
        internal val OPENABLE = Regex("""releases|reading-activity|book/\d{1,18}|shelf/[A-Z_]{1,20}\?search=false|add-ebook/[a-z0-9]{1,20}""")

        private val BOOK_TYPES = setOf("application/epub+zip", "application/pdf", "application/vnd.adobe.adept+xml")

        /** The book file an intent hands to MyBooks: the data of a View, or the stream of a Send; null for anything else. */
        @Suppress("DEPRECATION")
        fun bookUri(intent: Intent): Uri? {
            if (intent.type !in BOOK_TYPES) return null
            return when (intent.action) {
                Intent.ACTION_VIEW -> intent.data
                Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                else -> null
            }
        }
    }
}
