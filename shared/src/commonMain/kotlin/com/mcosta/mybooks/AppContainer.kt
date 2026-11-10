package com.mcosta.mybooks

import com.mcosta.mybooks.data.Appearance
import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.data.ReadingEvents
import com.mcosta.mybooks.data.SettingsPreferences
import com.mcosta.mybooks.data.authors.CatalogBooks
import com.mcosta.mybooks.data.authors.GoogleBooksAuthorSearch
import com.mcosta.mybooks.data.authors.WikipediaAuthors
import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.db.AppDatabase
import com.mcosta.mybooks.data.db.ShareStateDao
import com.mcosta.mybooks.data.ebooks.EbookFiles
import com.mcosta.mybooks.data.lookup.BnpClient
import com.mcosta.mybooks.data.lookup.GoogleBooksClient
import com.mcosta.mybooks.data.lookup.LookupChain
import com.mcosta.mybooks.data.lookup.OpenLibraryClient
import com.mcosta.mybooks.data.lookup.lookupHttpClient
import com.mcosta.mybooks.data.releases.ReleasesClient
import com.mcosta.mybooks.data.releases.ReleasesRepository
import com.mcosta.mybooks.data.suggestions.SubjectSearch
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingSummary
import com.mcosta.mybooks.library.DeviceServices
import com.mcosta.mybooks.library.Libraries
import com.mcosta.mybooks.library.LibraryFiles
import com.mcosta.mybooks.library.LibraryId
import com.mcosta.mybooks.library.LibrarySpace
import com.mcosta.mybooks.share.Publisher
import com.mcosta.mybooks.share.ShareLock
import com.mcosta.mybooks.share.ShareTracker
import com.mcosta.mybooks.share.SharingStatus
import com.mcosta.mybooks.social.SocialConfig
import com.mcosta.mybooks.social.SocialServices
import com.mcosta.mybooks.social.model.SessionState
import com.mcosta.mybooks.social.supabase.createSocialServices
import com.mcosta.mybooks.sync.LibrarySync
import com.mcosta.mybooks.sync.RemoteApplier
import com.mcosta.mybooks.sync.SyncDatabase
import com.mcosta.mybooks.sync.SyncRecorder
import com.mcosta.mybooks.sync.SyncStatus
import com.russhwolf.settings.Settings
import io.ktor.client.engine.HttpClientEngine
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path

/**
 * Hand-wired app dependencies, created once per app from the platform's pieces. Device-wide services live here; the
 * library's (books, quotes, ebooks, sync…) belong to the active [LibrarySpace] in [libraries], and the members below
 * that read it follow whichever library is active. Call [start] once before using it.
 */
class AppContainer(
    /** Opens a library's database: the no-account library's is today's `mybooks.db`. */
    openDatabase: (LibraryId) -> AppDatabase,
    /** Deletes an account library's database files. */
    deleteDatabase: (LibraryId) -> Unit,
    /** Where each library keeps its cover photos and ebook files. */
    libraryFiles: LibraryFiles,
    httpEngine: HttpClientEngine,
    googleBooksApiKey: String,
    userAgent: String,
    socialConfig: SocialConfig = SocialConfig("", ""),
    socialSettings: Settings? = null,
    /** Where small preferences (the library's sort order) are kept; null keeps them for the session only. */
    appSettings: Settings? = null,
    /** Where New in Portugal's downloaded lists are saved. */
    releasesFileSystem: FileSystem,
    releasesDir: Path,
) {
    /** The phone's file system (where ebooks and incoming files live). */
    val fileSystem: FileSystem = releasesFileSystem

    /** Outlives screens; used for writes that must finish after a screen closes. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }

    private val http = lookupHttpClient(httpEngine, userAgent)

    /** New in Portugal: monthly lists of new books, downloaded from GitHub Pages and saved on the phone. */
    val releases = ReleasesRepository(ReleasesClient(http), releasesFileSystem, releasesDir)

    /** The community features, or null when Supabase isn't configured (then they're hidden). */
    val social: SocialServices? = socialFor(socialConfig, httpEngine) { config, engine ->
        createSocialServices(config, engine, socialSettings)
    }

    /** Author biographies (Wikipedia) for the book page. */
    val authors = WikipediaAuthors(http)

    /** "More by …" on the book page (Google Books, by author). */
    val authorBooks = GoogleBooksAuthorSearch(http, apiKey = googleBooksApiKey)

    /** Catalogue books opened on their own page (from "More by …"), while the app runs. */
    val catalogBooks = CatalogBooks()

    /** Free classics from Project Gutenberg. */
    val classics = com.mcosta.mybooks.data.ebooks.ClassicsClient(http)

    /** Ebook files waiting for the "Add an ebook" screen. */
    val pendingFiles = com.mcosta.mybooks.ui.ebooks.PendingFiles()

    /** Books just finished, for the celebration. */
    val events = ReadingEvents()

    val preferences: Preferences? = appSettings?.let(::SettingsPreferences)

    /** Book colours or the wallpaper's, remembered. */
    val appearance = Appearance(preferences)

    private val lookup = LookupChain(
        listOf(
            BnpClient(http),
            GoogleBooksClient(http, apiKey = googleBooksApiKey),
            OpenLibraryClient(http),
        )
    )

    /** Public catalogues searched from Search when the library has no match (Google Books, Open Library; ISBNs through the lookup chain). */
    val publicSearch = com.mcosta.mybooks.data.lookup.PublicBookSearch(
        listOf(
            com.mcosta.mybooks.data.lookup.GoogleBooksTextSearch(http, apiKey = googleBooksApiKey),
            com.mcosta.mybooks.data.lookup.OpenLibraryTextSearch(http),
        ),
        lookup::lookup,
    )

    private val device = DeviceServices(
        lookup = lookup,
        authorBooks = authorBooks,
        subjects = SubjectSearch(http, apiKey = googleBooksApiKey),
        social = social,
        releases = releases,
        events = events,
        preferences = preferences,
        clock = clock,
    )

    /** The libraries on this phone; one is open at a time. */
    val libraries: Libraries<LibrarySpace> = Libraries(
        factory = { id ->
            val space = LibrarySpace(id, openDatabase(id), libraryFiles, device, applicationScope)
            try {
                space.open()
            } catch (e: Throwable) {
                if (e !is CancellationException) space.close()
                throw e
            }
            space
        },
        deviceSettings = preferences,
        scope = applicationScope,
        delete = { id ->
            deleteDatabase(id)
            libraryFiles.deleteAll(id)
        },
    )

    /** Opens the library active at the last launch; call once, before anything else uses the container. */
    suspend fun start() = libraries.start()

    private val space: LibrarySpace get() = libraries.active.value

    /** The active library's cover photos. */
    val coverStore: CoverStore get() = space.coverStore

    /** Stamps every local change and, while [sync] runs for a signed-in user, queues it for upload. */
    val syncRecorder: SyncRecorder get() = space.syncRecorder

    /** Whether the signed-in user shares their reading activity; null without the community. */
    val sharingStatus: SharingStatus? get() = space.sharingStatus

    /** Books marked "Not interested" in suggestions. */
    val hiddenSuggestions get() = space.hiddenSuggestions

    /** Tags shown as rows on the home screen. */
    val pinnedTags get() = space.pinnedTags

    /** The monthly notification of New in Portugal books by your authors and publishers. */
    val releaseAlerts get() = space.releaseAlerts

    /** Yearly reading challenges. */
    val challenges get() = space.challenges

    /** Quotes saved from books. */
    val quotes get() = space.quotes

    val repository get() = space.repository

    /** Book suggestions from the library (worked out once per session, or on refresh). */
    val suggestions get() = space.suggestions

    /** Looks up missing covers (e.g. after a Goodreads import) in the background. */
    val coverFinder get() = space.coverFinder

    /** Books' ebook files, reading positions and bookmarks. */
    val ebooks get() = space.ebooks

    /** What the home-screen widget shows, today, following the active library. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val readingSummaries: Flow<ReadingSummary>
        get() = libraries.active.flatMapLatest { it.readingSummaries }

    /** The library kept in step with the cloud while signed in; null without the community. See [LibrarySpace.sync]. */
    val sync: LibrarySync? get() = space.sync

    /**
     * Pushes and pulls now, and returns when that's done (the app back in the foreground, pull-to-refresh). A signed-in
     * user whose sync stopped (the session had expired, or signing out failed) gets it started again.
     */
    suspend fun syncNow() {
        val sync = sync ?: return
        (social?.auth?.session?.value as? SessionState.SignedIn)?.let { sync.start(it.userId) }
        sync.refresh()
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

/**
 * Activity sharing for [social], or null without the community. [loadBook] reads a book from the library; [uuidOf] gives a
 * book's uuid, also its public id.
 */
internal fun sharingFor(
    social: SocialServices?,
    dao: ShareStateDao,
    scope: CoroutineScope,
    uuidOf: suspend (Long) -> String? = { null },
    loadBook: suspend (Long) -> Book?,
): Sharing? {
    if (social == null) return null
    lateinit var status: SharingStatus
    val activeOwner = { status.state.value.activeOwner }
    val lock = ShareLock()
    val publisher = Publisher(dao, loadBook, social.activity, activeOwner, scope, lock = lock)
    val tracker = ShareTracker(dao, activeOwner, onPending = publisher::requestSync, lock = lock, uuidOf = uuidOf)
    status = SharingStatus(social, scope, onActivated = publisher::requestSync, onDisabled = tracker::forgetOwner)
    return Sharing(tracker, status, publisher)
}

/**
 * Library sync for [social], or null without the community (or where it has no sync endpoint). It runs while someone is
 * signed in: started on [SessionState.SignedIn]. Signed out, it stops if the user asked, else it waits for them to
 * sign in again ([LibrarySync.sessionEnded]). [db] must hold the RAW DAOs (see [LibrarySpace.sync]).
 */
internal fun syncFor(
    social: SocialServices?,
    db: SyncDatabase,
    recorder: SyncRecorder,
    scope: CoroutineScope,
    clock: () -> Long,
    shares: ShareTracker?,
    coverStore: CoverStore?,
    ebookFiles: EbookFiles?,
): LibrarySync? {
    val api = social?.sync ?: return null
    val applier = RemoteApplier(db, recorder, coverStore, shares, ebookFiles)
    val sync = LibrarySync(api, db, recorder, applier, clock, scope, shares = shares, coverStore = coverStore, ebookFiles = ebookFiles)
    scope.launch {
        social.auth.session.collect { session ->
            when (session) {
                is SessionState.SignedIn -> sync.start(session.userId)
                SessionState.SignedOut -> sync.sessionEnded()
                SessionState.Loading -> Unit
            }
        }
    }
    return sync
}

/**
 * Whether local changes are queued for upload: while [sync] runs for a signed-in user, while it waits for them to
 * sign in again (an expired session: its changes go then), and whenever this device's library belongs to a synced
 * account (at start, before the stored session is restored). Not after the user signed out: the library kept is local.
 */
internal suspend fun syncRecording(sync: LibrarySync?): Boolean =
    sync != null && (sync.status.value != SyncStatus.Off || sync.belongsToAnAccount())
