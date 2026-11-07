package com.mcosta.mybooks

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.mcosta.mybooks.data.Appearance
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.ChallengeRepository
import com.mcosta.mybooks.data.CoverFinder
import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.data.ReadingEvents
import com.mcosta.mybooks.data.authors.CatalogBooks
import com.mcosta.mybooks.data.suggestions.HiddenSuggestions
import com.mcosta.mybooks.data.suggestions.SubjectSearch
import com.mcosta.mybooks.data.suggestions.SuggestionEngine
import com.mcosta.mybooks.social.model.SessionState
import kotlinx.coroutines.flow.first
import com.mcosta.mybooks.data.authors.GoogleBooksAuthorSearch
import com.mcosta.mybooks.data.authors.WikipediaAuthors
import com.mcosta.mybooks.data.SettingsPreferences
import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.db.AppDatabase
import com.mcosta.mybooks.data.db.ShareStateDao
import com.mcosta.mybooks.data.lookup.BnpClient
import com.mcosta.mybooks.data.lookup.GoogleBooksClient
import com.mcosta.mybooks.data.lookup.LookupChain
import com.mcosta.mybooks.data.lookup.OpenLibraryClient
import com.mcosta.mybooks.data.lookup.lookupHttpClient
import com.mcosta.mybooks.data.releases.ReleasesClient
import com.mcosta.mybooks.data.releases.ReleasesRepository
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.share.Publisher
import com.mcosta.mybooks.share.ShareLock
import com.mcosta.mybooks.share.ShareTracker
import com.mcosta.mybooks.share.SharingStatus
import com.mcosta.mybooks.social.SocialConfig
import com.mcosta.mybooks.social.SocialServices
import com.mcosta.mybooks.social.supabase.createSocialServices
import com.russhwolf.settings.Settings
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import com.mcosta.mybooks.data.ebooks.EbookFiles
import com.mcosta.mybooks.data.ebooks.EbookRepository
import okio.Path

/** Hand-wired app dependencies, created once per app from the platform's pieces. */
class AppContainer(
    database: AppDatabase,
    httpEngine: HttpClientEngine,
    val coverStore: CoverStore,
    googleBooksApiKey: String,
    userAgent: String,
    socialConfig: SocialConfig = SocialConfig("", ""),
    socialSettings: Settings? = null,
    /** Where small preferences (the library's sort order) are kept; null keeps them for the session only. */
    appSettings: Settings? = null,
    /** Where New in Portugal's downloaded lists are saved. */
    releasesFileSystem: FileSystem,
    releasesDir: Path,
    /** Where ebook files are kept: `files/ebooks` on Android (next to the releases folder). */
    ebooksDir: Path = releasesDir.parent?.div("ebooks") ?: releasesDir / "ebooks",
) {
    private val ebookFiles = EbookFiles(releasesFileSystem, ebooksDir)

    /** Outlives screens; used for writes that must finish after a screen closes. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val http = lookupHttpClient(httpEngine, userAgent)

    /** New in Portugal: monthly lists of new books, downloaded from GitHub Pages and saved on the phone. */
    val releases = ReleasesRepository(ReleasesClient(http), releasesFileSystem, releasesDir)

    /** The community features, or null when Supabase isn't configured (then they're hidden). */
    val social: SocialServices? = socialFor(socialConfig, httpEngine) { config, engine ->
        createSocialServices(config, engine, socialSettings)
    }

    private val sharing: Sharing? = sharingFor(social, database.shareStateDao(), applicationScope) { id -> repository.getBook(id) }

    /** Whether the signed-in user shares their reading activity; null without the community. */
    val sharingStatus: SharingStatus? = sharing?.status

    /** Author biographies (Wikipedia) for the book page. */
    val authors = WikipediaAuthors(http)

    /** "More by …" on the book page (Google Books, by author). */
    val authorBooks = GoogleBooksAuthorSearch(http, apiKey = googleBooksApiKey)

    /** Catalogue books opened on their own page (from "More by …"), while the app runs. */
    val catalogBooks = CatalogBooks()

    /** Free classics from Project Gutenberg. */
    val classics = com.mcosta.mybooks.data.ebooks.ClassicsClient(http)

    /** Books just finished, for the celebration. */
    val events = ReadingEvents()

    val preferences: Preferences? = appSettings?.let(::SettingsPreferences)

    /** Books marked "Not interested" in suggestions. */
    val hiddenSuggestions = HiddenSuggestions(preferences)

    /** Tags shown as rows on the home screen. */
    val pinnedTags = com.mcosta.mybooks.data.PinnedTags(preferences)

    /** The monthly notification of New in Portugal books by your authors and publishers. */
    val releaseAlerts = com.mcosta.mybooks.data.releases.ReleaseAlerts(releases, { repository.observeBooks(null, "").first() }, preferences)

    /** Yearly reading challenges. */
    val challenges = ChallengeRepository(database.challengeDao())

    /** Quotes saved from books. */
    val quotes = com.mcosta.mybooks.data.QuoteRepository(database.quoteDao())

    val repository = BookRepository(
        dao = database.bookDao(),
        lookupService = LookupChain(
            listOf(
                BnpClient(http),
                GoogleBooksClient(http, apiKey = googleBooksApiKey),
                OpenLibraryClient(http),
            )
        ),
        transactionRunner = { block -> database.useWriterConnection { it.immediateTransaction { block() } } },
        coverStore = coverStore,
        shareTracker = sharing?.tracker,
        challenges = challenges,
        events = events,
        hiddenSuggestions = hiddenSuggestions,
        quotes = database.quoteDao(),
        ebooks = database.ebookDao(),
        bookmarks = database.bookmarkDao(),
        ebookFiles = ebookFiles,
    )

    /** Book suggestions from the library (worked out once per session, or on refresh). */
    val suggestions = SuggestionEngine(
        library = { repository.observeBooks(null, "").first() },
        authorBooks = authorBooks,
        subjects = SubjectSearch(http, apiKey = googleBooksApiKey),
        community = social?.let { s ->
            { liked: List<String> -> if (s.auth.session.value is SessionState.SignedIn) s.activity.communitySuggestions(liked) else emptyList() }
        },
        releases = { releases.allMonths().months },
    )


    /** Book colours or the wallpaper's, remembered. */
    val appearance = Appearance(preferences)

    /** Looks up missing covers (e.g. after a Goodreads import) in the background. */
    val coverFinder = CoverFinder(repository, applicationScope)

    /** Books' ebook files, reading positions and bookmarks. */
    val ebooks = EbookRepository(database.ebookDao(), database.bookmarkDao(), ebookFiles, repository)

    /** What the home-screen widget shows, today, following the library. */
    val readingSummaries: kotlinx.coroutines.flow.Flow<com.mcosta.mybooks.domain.ReadingSummary> =
        kotlinx.coroutines.flow.combine(repository.observeBooks(null, ""), challenges.observe()) { books, goals ->
            com.mcosta.mybooks.domain.readingSummary(books, goals, com.mcosta.mybooks.ui.today(), kotlinx.datetime.TimeZone.currentSystemDefault())
        }
}

/** The social services when [config] is complete, else null (nothing is created or contacted). */
internal fun socialFor(
    config: SocialConfig,
    engine: HttpClientEngine,
    factory: (SocialConfig, HttpClientEngine) -> SocialServices = ::createSocialServices,
): SocialServices? = if (config.enabled) factory(config, engine) else null

/** The pieces that share reading activity, wired to each other. */
internal class Sharing(val tracker: ShareTracker, val status: SharingStatus, val publisher: Publisher)

/** Activity sharing for [social], or null without the community. [loadBook] reads a book from the library. */
internal fun sharingFor(
    social: SocialServices?,
    dao: ShareStateDao,
    scope: CoroutineScope,
    loadBook: suspend (Long) -> Book?,
): Sharing? {
    if (social == null) return null
    lateinit var status: SharingStatus
    val activeOwner = { status.state.value.activeOwner }
    val lock = ShareLock()
    val publisher = Publisher(dao, loadBook, social.activity, activeOwner, scope, lock = lock)
    val tracker = ShareTracker(dao, activeOwner, onPending = publisher::requestSync, lock = lock)
    status = SharingStatus(social, scope, onActivated = publisher::requestSync, onDisabled = tracker::forgetOwner)
    return Sharing(tracker, status, publisher)
}
