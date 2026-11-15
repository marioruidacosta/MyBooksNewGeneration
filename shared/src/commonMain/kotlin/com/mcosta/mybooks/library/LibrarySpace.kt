package com.mcosta.mybooks.library

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.mcosta.mybooks.Sharing
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.ChallengeRepository
import com.mcosta.mybooks.data.CoverFinder
import com.mcosta.mybooks.data.PinnedTags
import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.data.QuoteRepository
import com.mcosta.mybooks.data.ReadingEvents
import com.mcosta.mybooks.data.authors.GoogleBooksAuthorSearch
import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.db.AppDatabase
import com.mcosta.mybooks.data.ebooks.EbookFiles
import com.mcosta.mybooks.data.ebooks.EbookRepository
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.releases.ReleaseAlerts
import com.mcosta.mybooks.data.releases.ReleasesRepository
import com.mcosta.mybooks.data.suggestions.HiddenSuggestions
import com.mcosta.mybooks.data.suggestions.SubjectSearch
import com.mcosta.mybooks.data.suggestions.SuggestionEngine
import com.mcosta.mybooks.domain.ReadingSummary
import com.mcosta.mybooks.domain.readingSummary
import com.mcosta.mybooks.share.SharingStatus
import com.mcosta.mybooks.sharingFor
import com.mcosta.mybooks.social.SocialServices
import com.mcosta.mybooks.social.model.SessionState
import com.mcosta.mybooks.sync.LibraryPrefs
import com.mcosta.mybooks.sync.LibrarySync
import com.mcosta.mybooks.sync.SyncDatabase
import com.mcosta.mybooks.sync.SyncRecorder
import com.mcosta.mybooks.sync.SyncTable
import com.mcosta.mybooks.sync.SyncingBookDao
import com.mcosta.mybooks.sync.SyncingBookmarkDao
import com.mcosta.mybooks.sync.SyncingChallengeDao
import com.mcosta.mybooks.sync.SyncingEbookDao
import com.mcosta.mybooks.sync.SyncingQuoteDao
import com.mcosta.mybooks.syncFor
import com.mcosta.mybooks.syncRecording
import com.mcosta.mybooks.ui.today
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone

/** What a library needs from the device: services that aren't kept per library. */
class DeviceServices(
    /** Finds books by ISBN (BNP, Google Books, Open Library). */
    val lookup: BookLookupService,
    /** "More by …" (Google Books, by author). */
    val authorBooks: GoogleBooksAuthorSearch,
    /** Books by subject, for suggestions. */
    val subjects: SubjectSearch,
    /** The community features, or null when they aren't configured. */
    val social: SocialServices?,
    /** New in Portugal's lists. */
    val releases: ReleasesRepository,
    /** Books just finished, for the celebration. */
    val events: ReadingEvents,
    /** The device's own settings (appearance, notifications, sort order…). */
    val preferences: Preferences?,
    val clock: () -> Long,
)

/**
 * One library, open: its database, cover and ebook folders, preferences, and everything built on them. Close it with
 * [close] once nothing uses it.
 */
class LibrarySpace(
    override val id: LibraryId,
    private val database: AppDatabase,
    files: LibraryFiles,
    device: DeviceServices,
    scope: CoroutineScope,
) : LibraryHandle {
    private val job: Job = SupervisorJob(scope.coroutineContext[Job])

    /** This library's background work (sync, sharing, cover searches…): cancelled when it closes. */
    private val spaceScope = CoroutineScope(scope.coroutineContext + job)

    /** Pinned tags' and "Not interested" changes being recorded: [close] lets them finish rather than cancel them. */
    private val prefJob: CompletableJob = SupervisorJob(job)
    private val prefScope = CoroutineScope(scope.coroutineContext + prefJob)

    private val writesJob: CompletableJob = SupervisorJob(job)

    /**
     * Where screens run writes that must outlive them (a swipe-delete past its Undo, a note saved on leaving): [close]
     * waits for them, so none lands on a closed database or is lost to a switch.
     */
    val writes = CoroutineScope(scope.coroutineContext + writesJob)

    /** Cover photos, in this library's folder. */
    val coverStore = CoverStore(files.fileSystem, files.coversDir(id))

    /** Ebook files, in this library's folder (the no-account library's also holds the accounts' folders). */
    val ebookFiles = EbookFiles(files.fileSystem, files.ebooksDir(id))

    /** Its raw tables and folders, for moving books into or out of it ([LibraryTransfer]). */
    override val contents: LibraryContents by lazy {
        LibraryContents(
            SyncDatabase(
                books = database.bookDao(),
                quotes = database.quoteDao(),
                ebooks = database.ebookDao(),
                bookmarks = database.bookmarkDao(),
                challenges = database.challengeDao(),
                queue = database.syncQueueDao(),
                state = database.syncStateDao(),
            ),
            syncRecorder, coverStore, ebookFiles, files.fileSystem,
        )
    }

    private val writeTransaction: suspend (suspend () -> Unit) -> Unit =
        { block -> database.useWriterConnection { it.immediateTransaction { block() } } }

    /** Stamps every local change and, while [sync] runs for a signed-in user, queues it for upload. */
    val syncRecorder = SyncRecorder(database.syncQueueDao(), device.clock, { syncRecording(sync) }, writeTransaction)

    /** A preference changed: keep its time and queue it. */
    private val prefChanged: suspend (String) -> Unit = { key ->
        syncRecorder.transaction {
            database.syncStateDao().set("prefs/$key", syncRecorder.now().toString())
            syncRecorder.record(SyncTable.PREFS, key)
        }
    }

    /**
     * Where pinned tags and "Not interested" are kept: the library's own database (the no-account library's too, since
     * [LibraryUpgrade] moved them out of the device settings).
     */
    private val libraryPrefs = DbPreferences(database.syncStateDao(), spaceScope)

    private val sharing: Sharing? = sharingFor(
        id, device.social, database.shareStateDao(), spaceScope,
        uuidOf = { bookId -> database.bookDao().getById(bookId)?.uuid },
    ) { bookId -> repository.getBook(bookId) }

    /** Whether this account shares its reading activity; null for the no-account library (it never publishes) and without the community. */
    val sharingStatus: SharingStatus? = sharing?.status

    /** Books marked "Not interested" in suggestions. */
    val hiddenSuggestions = HiddenSuggestions(libraryPrefs, prefChanged, prefScope)

    /** Tags shown as rows on the home screen. */
    val pinnedTags = PinnedTags(libraryPrefs, prefChanged, prefScope)

    /** Yearly reading challenges. */
    val challenges = ChallengeRepository(SyncingChallengeDao(database.challengeDao(), syncRecorder))

    /** Quotes saved from books. */
    val quotes = QuoteRepository(SyncingQuoteDao(database.quoteDao(), syncRecorder))

    val repository = BookRepository(
        dao = SyncingBookDao(database.bookDao(), syncRecorder),
        lookupService = device.lookup,
        transactionRunner = { block -> syncRecorder.transaction { block() } },
        coverStore = coverStore,
        shareTracker = sharing?.tracker,
        challenges = challenges,
        events = device.events,
        hiddenSuggestions = hiddenSuggestions,
        quotes = SyncingQuoteDao(database.quoteDao(), syncRecorder),
        ebooks = SyncingEbookDao(database.ebookDao(), database.bookDao(), syncRecorder),
        bookmarks = SyncingBookmarkDao(database.bookmarkDao(), syncRecorder),
        ebookFiles = ebookFiles,
    )

    /** The monthly notification of New in Portugal books by this library's authors and publishers (on/off per device). */
    val releaseAlerts = ReleaseAlerts(device.releases, { repository.observeBooks(null, "").first() }, device.preferences)

    /** Book suggestions from the library (worked out once per session, or on refresh). */
    val suggestions = SuggestionEngine(
        library = { repository.observeBooks(null, "").first() },
        authorBooks = device.authorBooks,
        subjects = device.subjects,
        community = device.social?.let { s ->
            { liked: List<String> -> if (s.auth.session.value is SessionState.SignedIn) s.activity.communitySuggestions(liked) else emptyList() }
        },
        releases = { device.releases.allMonths().months },
    )

    /** Looks up missing covers (e.g. after a Goodreads import) in the background. */
    val coverFinder = CoverFinder(repository, spaceScope)

    /** Books' ebook files, reading positions and bookmarks. */
    val ebooks = EbookRepository(
        SyncingEbookDao(database.ebookDao(), database.bookDao(), syncRecorder),
        SyncingBookmarkDao(database.bookmarkDao(), syncRecorder),
        ebookFiles,
        repository,
    )

    /** What the home-screen widget shows, today, following the library. */
    val readingSummaries: Flow<ReadingSummary> =
        combine(repository.observeBooks(null, ""), challenges.observe()) { books, goals ->
            readingSummary(books, goals, today(), TimeZone.currentSystemDefault())
        }

    /**
     * This account library's sync; null for the no-account library (it never syncs) and without the community.
     * [LibrarySessions] starts it, for this library's own account, once the library is active and open. Declared last:
     * starting it runs work on other threads, which must find the rest of the library built.
     *
     * WARNING: the [SyncDatabase] must hold the RAW DAOs (`database.bookDao()` …), never the `Syncing*` wrappers. They
     * would compile, but every pulled row would be stamped and queued again (sent back to the server, forever) and,
     * through the repository, published as this phone's activity.
     */
    override val sync: LibrarySync? = syncFor(
        id,
        device.social,
        SyncDatabase(
            books = database.bookDao(),
            quotes = database.quoteDao(),
            ebooks = database.ebookDao(),
            bookmarks = database.bookmarkDao(),
            challenges = database.challengeDao(),
            queue = database.syncQueueDao(),
            state = database.syncStateDao(),
            prefs = LibraryPrefs(pinnedTags, hiddenSuggestions),
            shareState = database.shareStateDao(),
        ),
        syncRecorder, spaceScope, device.clock, sharing?.tracker, coverStore, ebookFiles,
    )

    /** Loads this library's preferences (its first database query); call once, off the main thread, before it's used. */
    override suspend fun open() {
        libraryPrefs.load()
        pinnedTags.reload()
        hiddenSuggestions.reload()
    }

    /**
     * Stops its sync, lets the preference changes being recorded and the screens' last [writes] finish (their queue
     * entries go with the database), stops its other background work (waiting for it to end), writes its pending
     * preferences, then closes its database. Call it from outside this library's own work, which it waits for.
     */
    override suspend fun close() {
        sync?.stop()
        prefJob.complete()
        writesJob.complete()
        prefJob.join()
        writesJob.join()
        job.cancelAndJoin()
        libraryPrefs.flush()
        database.close()
    }
}
