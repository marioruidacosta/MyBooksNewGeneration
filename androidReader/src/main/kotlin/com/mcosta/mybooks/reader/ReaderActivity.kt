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
import com.mcosta.mybooks.reader.ui.ReaderActions
import com.mcosta.mybooks.reader.ui.ReaderBars
import com.mcosta.mybooks.ui.theme.MyBooksTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.SelectableNavigator
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

/** What the reader's bars show. */
data class ReaderChrome(
    val title: String = "",
    val label: String = "",
    val visible: Boolean = true,
    val format: EbookFormat? = null,
    /** 0–1 through the book, for the position slider. */
    val progression: Double = 0.0,
    val contents: List<TocRow> = emptyList(),
)

/**
 * Reads a book's EPUB or PDF with Readium, from where reading stopped. Swipe or tap the side edges to turn pages;
 * a tap in the middle shows or hides the bars. The place is saved as it moves (see [ReaderViewModel]).
 */
class ReaderActivity : FragmentActivity(), ReaderActions {
    private val bookId by lazy { intent.getLongExtra(EXTRA_BOOK, -1L) }
    private val chrome = MutableStateFlow(ReaderChrome())
    private val container by lazy { (application as ContainerOwner).container }
    private val looks by lazy { ReadingLooks(container.preferences) }
    private var publication: Publication? = null
    private var navigator: VisualNavigator? = null
    /** Readium links by the target given to [TocEntry], to go back from the contents. */
    private val links = mutableMapOf<String, Link>()

    private val viewModel: ReaderViewModel by viewModels {
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
                    bookmarks = container.ebooks.observeBookmarks(bookId),
                    addBookmark = { position, label -> container.ebooks.addBookmark(bookId, position, label) },
                    deleteBookmark = { container.ebooks.deleteBookmark(it) },
                    quotes = container.quotes.observe(bookId),
                    addHighlight = { text, position -> container.quotes.addHighlight(bookId, text, position) },
                    deleteQuote = { container.quotes.delete(it) },
                    bookSearch = ReadiumSearch { publication },
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
                MyBooksTheme { ReaderBars(chrome, looks.look, viewModel, this@ReaderActivity, onBack = { finish() }) }
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

    private fun tocEntry(link: Link): TocEntry {
        val target = link.href.toString() + "#" + links.size
        links[target] = link
        return TocEntry(link.title.orEmpty(), target, link.children.map(::tocEntry))
    }

    private suspend fun show(publication: Publication, file: ReaderFile, containerId: Int) {
        val start = file.position?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
        val pages = publication.positions().size
        chrome.value = chrome.value.copy(
            title = publication.metadata.title.orEmpty(),
            format = file.format,
            contents = flattenContents(publication.tableOfContents.map(::tocEntry)),
        )
        supportFragmentManager.fragmentFactory = when (file.format) {
            EbookFormat.EPUB -> EpubNavigatorFactory(publication).createFragmentFactory(
                initialLocator = start,
                initialPreferences = looks.look.value.toEpub(),
                configuration = EpubNavigatorFragment.Configuration { selectionActionModeCallback = HighlightActionMode(::highlightSelection) },
            )
            EbookFormat.PDF -> PdfNavigatorFactory(publication, PdfiumEngineProvider()).createFragmentFactory(initialLocator = start)
        }
        val fragmentClass = when (file.format) {
            EbookFormat.EPUB -> EpubNavigatorFragment::class.java
            EbookFormat.PDF -> PdfNavigatorFragment::class.java
        }
        supportFragmentManager.commitNow { add(containerId, fragmentClass, Bundle(), NAVIGATOR) }
        val nav = supportFragmentManager.findFragmentByTag(NAVIGATOR) as VisualNavigator
        navigator = nav
        (nav as? OverflowableNavigator)?.let { nav.addInputListener(DirectionalNavigationAdapter(it)) }
        (nav as? DecorableNavigator)?.let { decorable ->
            decorable.addDecorationListener(
                HIGHLIGHTS,
                object : DecorableNavigator.Listener {
                    override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                        val id = quoteIdOf(event.decoration.id) ?: return false
                        viewModel.showHighlight(id)
                        return true
                    }
                },
            )
            lifecycleScope.launch { viewModel.highlights.collect { decorable.applyDecorations(highlightDecorations(it), HIGHLIGHTS) } }
        }
        nav.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                val width = window.decorView.width.takeIf { it > 0 } ?: return false
                // The middle third shows or hides the bars; the edges turn pages (DirectionalNavigationAdapter).
                if (event.point.x < width / 3f || event.point.x > width * 2f / 3f) return false
                chrome.value = chrome.value.copy(visible = !chrome.value.visible)
                return true
            }
        })
        lifecycleScope.launch {
            viewModel.goTo.collect { json ->
                runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()?.let { nav.go(it, animated = false) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                nav.currentLocator.collect { locator ->
                    val progression = locator.locations.totalProgression ?: locator.locations.progression ?: 0.0
                    viewModel.position(
                        locator.toJSON().toString(), progression,
                        bookmarkLabel(file.format, locator.title, locator.locations.position, progression),
                    )
                    chrome.value = chrome.value.copy(
                        progression = progression,
                        label = positionLabel(file.format, locator.title, locator.locations.position, progression, pages),
                    )
                }
            }
        }
    }

    /** "Highlight" in the selection menu: the selected text becomes a quote at its place. */
    private fun highlightSelection() {
        val selectable = navigator as? SelectableNavigator ?: return
        lifecycleScope.launch {
            val selection = selectable.currentSelection() ?: return@launch
            viewModel.highlight(selection.locator.text.highlight.orEmpty(), selection.locator.toJSON().toString())
            selectable.clearSelection()
        }
    }

    override fun goTo(entry: TocEntry) {
        links[entry.target]?.let { navigator?.go(it, animated = false) }
    }

    override fun seek(progression: Double) {
        val pub = publication ?: return
        lifecycleScope.launch { pub.locateProgression(progression)?.let { navigator?.go(it, animated = false) } }
    }

    override fun setLook(look: ReadingLook) {
        looks.set(look)
        (navigator as? EpubNavigatorFragment)?.submitPreferences(look.toEpub())
    }

    private fun ReadingLook.toEpub() = EpubPreferences(
        fontFamily = when (font) {
            LookFont.BOOK -> null
            LookFont.SERIF -> FontFamily.SERIF
            LookFont.SANS -> FontFamily.SANS_SERIF
        },
        fontSize = fontSize,
        lineHeight = lineHeight,
        // The book's own styles would override the chosen font and spacing.
        publisherStyles = if (font == LookFont.BOOK && lineHeight == null) null else false,
        theme = when (theme) {
            LookTheme.LIGHT -> Theme.LIGHT
            LookTheme.SEPIA -> Theme.SEPIA
            LookTheme.DARK -> Theme.DARK
        },
    )

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
