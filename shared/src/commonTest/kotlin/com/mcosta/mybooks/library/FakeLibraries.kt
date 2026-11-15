package com.mcosta.mybooks.library

import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.FakeBookmarkDao
import com.mcosta.mybooks.data.FakeChallengeDao
import com.mcosta.mybooks.data.FakeEbookDao
import com.mcosta.mybooks.data.FakeQuoteDao
import com.mcosta.mybooks.data.FakeShareStateDao
import com.mcosta.mybooks.data.FakeSyncQueueDao
import com.mcosta.mybooks.data.FakeSyncStateDao
import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.data.db.BookEntity
import com.mcosta.mybooks.data.db.toBook
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.sharingFor
import com.mcosta.mybooks.social.FakeSocial
import com.mcosta.mybooks.social.SocialServices
import com.mcosta.mybooks.sync.LibrarySync
import com.mcosta.mybooks.sync.SyncDatabase
import com.mcosta.mybooks.sync.SyncRecorder
import com.mcosta.mybooks.sync.SyncingBookDao
import com.mcosta.mybooks.syncFor
import com.mcosta.mybooks.syncRecording
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class MemoryPreferences : Preferences {
    val values = mutableMapOf<String, String>()
    override fun get(key: String) = values[key]
    override fun set(key: String, value: String) {
        values[key] = value
    }
}

/** One library's tables, kept from one opening to the next: its "database file". */
class FakeLibraryStore {
    val books = FakeBookDao()
    val queue = FakeSyncQueueDao()
    val state = FakeSyncStateDao()
    val shares = FakeShareStateDao()
    val db = SyncDatabase(books, FakeQuoteDao(), FakeEbookDao(), FakeBookmarkDao(), FakeChallengeDao(), queue, state)

    fun live(): List<BookEntity> = books.rows.value.values.filter { !it.deleted }
}

/**
 * A [LibrarySpace] over fake tables: built with the app's own rules for whether a library syncs ([syncFor]), shares
 * ([sharingFor]) and queues its changes ([syncRecording]).
 */
class FakeLibrarySpace(
    override val id: LibraryId,
    val store: FakeLibraryStore,
    social: SocialServices?,
    parent: CoroutineScope,
    clock: () -> Long,
    private val log: MutableList<String>,
    /** Completed when [open] may finish (null: at once). */
    private val openGate: CompletableDeferred<Unit>?,
) : LibraryHandle {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)

    val recorder = SyncRecorder(store.queue, clock, { syncRecording(sync) }, { it() })

    private val sharing = sharingFor(id, social, store.shares, scope) { bookId -> store.books.getById(bookId)?.toBook() }

    override val sync: LibrarySync? = syncFor(id, social, store.db, recorder, scope, clock, sharing?.tracker, null, null)

    private val books = SyncingBookDao(store.books, recorder)

    var opened = false
        private set
    var closed = false
        private set

    /** Adds a book as the app does: stamped, queued (if this library records) and shared (if it shares). */
    suspend fun addBook(title: String): BookEntity {
        val id = books.insert(
            BookEntity(
                isbn = null, title = title, authors = "Eça de Queirós", publisher = null, publishedYear = null,
                pageCount = null, coverUrl = null, description = null, status = ReadingStatus.READ, rating = null,
                notes = "", dateAdded = 0, dateRead = null, searchText = title.lowercase(),
            ),
        )
        val book = store.books.getById(id)!!
        sharing?.tracker?.changed(null, book.toBook())
        return book
    }

    override suspend fun open() {
        openGate?.await()
        opened = true
    }

    override suspend fun close() {
        log += "close ${id.key}: sync ${sync?.status?.value ?: "none"}"
        sync?.stop()
        job.cancelAndJoin()
        closed = true
    }
}

/**
 * The app's libraries over fake tables and a fake file system, following [social]'s session as the app does: what
 * [com.mcosta.mybooks.AppContainer] wires, with fakes. Engines run in [engines], on the test's scheduler.
 */
class FakeLibraries(private val test: TestScope, val social: FakeSocial, engines: Job) {
    val fs = FakeFileSystem()
    val files = LibraryFiles(fs, "/files".toPath())
    val stores = mutableMapOf<LibraryId, FakeLibraryStore>()
    val settings = MemoryPreferences()
    val deleted = mutableListOf<LibraryId>()
    val log = mutableListOf<String>()

    /** A library that can't be opened. */
    var failing: LibraryId? = null

    /** Holds the next opening of a library until completed. */
    var openGate: CompletableDeferred<Unit>? = null

    val scope = CoroutineScope(StandardTestDispatcher(test.testScheduler) + engines)

    val libraries: Libraries<FakeLibrarySpace> = Libraries(
        factory = { id ->
            if (id == failing) error("can't open")
            val gate = openGate.also { openGate = null }
            FakeLibrarySpace(id, stores.getOrPut(id) { FakeLibraryStore() }, social.services, scope, { test.testScheduler.currentTime }, log, gate)
        },
        deviceSettings = settings,
        scope = scope,
        delete = { id ->
            stores.remove(id)
            files.deleteAll(id)
            deleted += id
        },
    )

    val sessions = LibrarySessions(libraries, social.auth.session, scope)

    val active: FakeLibrarySpace get() = libraries.active.value

    /** As the app starts: the last library at once, then (in the background) its preferences, then the session. */
    suspend fun start() {
        libraries.start()
        scope.launch {
            libraries.openActive()
            sessions.follow()
        }
    }

    /** [user] signs in; the fake cloud now answers as [user]. */
    fun signIn(user: String) {
        social.sync.owner = user
        social.signInAs(user)
    }

    /** "Sign out" in Settings → Account, with "Keep" or "Remove". */
    suspend fun signOut(keep: Boolean) = sessions.signOut(keep) { social.auth.signOut() }

    /** Puts a cover photo and an ebook file in [id]'s folders; returns their paths. */
    fun putFiles(id: LibraryId): List<Path> {
        val cover = files.coversDir(id) / "1.jpg"
        val ebook = files.ebooksDir(id) / "1.epub"
        for (path in listOf(cover, ebook)) {
            fs.createDirectories(path.parent!!)
            fs.write(path) { writeUtf8(path.toString()) }
        }
        return listOf(cover, ebook)
    }
}
