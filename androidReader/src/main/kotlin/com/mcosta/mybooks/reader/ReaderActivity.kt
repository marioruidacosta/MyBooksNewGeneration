package com.mcosta.mybooks.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mcosta.mybooks.ContainerOwner
import com.mcosta.mybooks.domain.EbookFormat
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.reader.ui.ReaderBars
import com.mcosta.mybooks.ui.theme.MyBooksTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File
import kotlin.math.roundToInt

/** What the reader's bars show. */
data class ReaderChrome(val title: String = "", val label: String = "", val visible: Boolean = true)

/**
 * Reads a book's EPUB or PDF with Readium, from where reading stopped. Swipe or tap the side edges to turn pages;
 * a tap in the middle shows or hides the bars. The place is saved as it moves (see [ReaderViewModel]).
 */
class ReaderActivity : FragmentActivity() {
    private val bookId by lazy { intent.getLongExtra(EXTRA_BOOK, -1L) }
    private val chrome = MutableStateFlow(ReaderChrome())
    private var publication: Publication? = null

    private val viewModel: ReaderViewModel by viewModels {
        val container = (application as ContainerOwner).container
        viewModelFactory {
            initializer {
                ReaderViewModel(
                    load = {
                        val state = container.ebooks.observe(bookId).first()
                        if (state == null || !state.fileOnPhone) null
                        else {
                            container.ebooks.opened(bookId)
                            ReaderFile(state.ebook.format, container.ebooks.fileOf(state.ebook).toString(), state.ebook.position)
                        }
                    },
                    save = { position, progression -> container.ebooks.savePosition(bookId, position, progression) },
                    markRead = { container.repository.setStatus(bookId, ReadingStatus.READ) },
                    scope = container.applicationScope,
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Fragments are not restored: the book is opened again, at its saved place.
        super.onCreate(null)
        enableEdgeToEdge()
        val frame = FragmentContainerView(this).apply { id = View.generateViewId() }
        val bars = ComposeView(this).apply {
            setContent {
                MyBooksTheme {
                    ReaderBars(chrome, viewModel, onBack = { finish() })
                }
            }
        }
        setContentView(
            FrameLayout(this).apply {
                addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(bars, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            },
        )
        lifecycleScope.launch {
            val load = viewModel.load.first { it !is ReaderLoad.Loading }
            if (load !is ReaderLoad.Ready) return@launch fail((load as ReaderLoad.Failed).message)
            val opened = open(load.file) ?: return@launch fail("Couldn't open this book")
            publication = opened
            show(opened, load.file, frame.id)
        }
    }

    private suspend fun open(file: ReaderFile): Publication? {
        val http = DefaultHttpClient()
        val assets = AssetRetriever(contentResolver, http)
        val opener = PublicationOpener(DefaultPublicationParser(this, http, assets, PdfiumDocumentFactory(this)))
        val asset = assets.retrieve(File(file.path)).getOrNull() ?: return null
        return opener.open(asset, allowUserInteraction = false).getOrNull()
    }

    private suspend fun show(publication: Publication, file: ReaderFile, containerId: Int) {
        val start = file.position?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
        val total = publication.positions().size
        chrome.value = chrome.value.copy(title = publication.metadata.title.orEmpty())
        supportFragmentManager.fragmentFactory = when (file.format) {
            EbookFormat.EPUB -> EpubNavigatorFactory(publication).createFragmentFactory(initialLocator = start)
            EbookFormat.PDF -> PdfNavigatorFactory(publication, PdfiumEngineProvider()).createFragmentFactory(initialLocator = start)
        }
        val fragmentClass = when (file.format) {
            EbookFormat.EPUB -> EpubNavigatorFragment::class.java
            EbookFormat.PDF -> PdfNavigatorFragment::class.java
        }
        supportFragmentManager.commitNow { add(containerId, fragmentClass, Bundle(), NAVIGATOR) }
        val navigator = supportFragmentManager.findFragmentByTag(NAVIGATOR) as VisualNavigator
        (navigator as? org.readium.r2.navigator.OverflowableNavigator)?.let { navigator.addInputListener(DirectionalNavigationAdapter(it)) }
        navigator.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                val width = window.decorView.width.takeIf { it > 0 } ?: return false
                // The middle third shows or hides the bars; the edges turn pages (DirectionalNavigationAdapter).
                if (event.point.x < width / 3f || event.point.x > width * 2f / 3f) return false
                toggleBars()
                return true
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                navigator.currentLocator.collect { locator ->
                    val progression = locator.locations.totalProgression ?: locator.locations.progression ?: 0.0
                    viewModel.position(locator.toJSON().toString(), progression)
                    chrome.value = chrome.value.copy(label = label(file.format, locator, progression, total))
                }
            }
        }
    }

    private fun label(format: EbookFormat, locator: Locator, progression: Double, total: Int): String {
        val percent = "${(progression * 100).roundToInt().coerceIn(0, 100)}%"
        return when (format) {
            EbookFormat.PDF -> locator.locations.position?.let { "Page $it of $total" } ?: percent
            EbookFormat.EPUB -> listOfNotNull(locator.title?.takeIf { it.isNotBlank() }, percent).joinToString(" · ")
        }
    }

    private fun toggleBars() {
        chrome.value = chrome.value.copy(visible = !chrome.value.visible)
    }

    private fun fail(message: String) {
        viewModel.failed(message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onStop() {
        viewModel.leave()
        super.onStop()
    }

    override fun onDestroy() {
        publication?.close()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_BOOK = "book-id"
        private const val NAVIGATOR = "navigator"

        fun start(context: Context, bookId: Long) {
            context.startActivity(Intent(context, ReaderActivity::class.java).putExtra(EXTRA_BOOK, bookId))
        }
    }
}
