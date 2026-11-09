package com.mcosta.mybooks.sync

import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.ebooks.EbookFiles
import com.mcosta.mybooks.share.ShareTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** `sync_state` key of the last `seq` pulled. */
internal const val SEQ_KEY = "seq"

/** `sync_push` takes at most this many changes per call. */
internal const val PUSH_BATCH = 200

/** Rows asked for per `sync_pull`; a shorter page is the last one. */
internal const val PULL_MAX = 500

/**
 * Keeps the library in step with the cloud while signed in.
 *
 * One worker per [start]ed session runs passes, one at a time: push the queue (batches of [PUSH_BATCH]), then pull
 * (pages of [PULL_MAX]). A pass runs on start, on a local change ([SyncRecorder.changes]), on a Realtime ping (and each
 * time the ping channel is (re)subscribed, since pings can be missed while it reconnects), on [refresh] and on
 * [connectivityRestored]. After a failure it waits [retryDelays] (the last one repeating) before trying again;
 * [connectivityRestored] and [refresh] cut the wait short. A session that expired (AUTH) stops until [start] is called
 * again, keeping the queue. An account's first pass on this device runs the first sign-in merge ([FirstMerge]) before
 * anything else ([mergeProgress] counts it). [shares], [coverStore] and [ebookFiles] are what that merge and [signOut]
 * touch besides the database.
 *
 * Everything runs in [scope] (the app's, off the main thread).
 */
class LibrarySync(
    private val api: SyncApi,
    private val db: SyncDatabase,
    private val recorder: SyncRecorder,
    private val applier: RemoteApplier,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val retryDelays: List<Long> = listOf(5_000, 15_000, 60_000, 300_000),
    shares: ShareTracker? = null,
    private val coverStore: CoverStore? = null,
    private val ebookFiles: EbookFiles? = null,
) {
    init {
        require(retryDelays.isNotEmpty()) { "retryDelays is empty" }
    }

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Off)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _setAside = MutableStateFlow(0)

    /** Changes the server refused (set aside) since [start]; they left the queue, the device keeps its values. */
    val setAside: StateFlow<Int> = _setAside.asStateFlow()

    private val firstMerge = FirstMerge(api, db, applier, shares, coverStore) { n -> _setAside.update { it + n } }

    private val coverSync = coverStore?.let {
        CoverSync(api, it, db.state, clock) { write ->
            // Written between passes, and never once the session has stopped (sign-out erases next).
            passLock.withLock {
                currentCoroutineContext().ensureActive()
                write()
            }
        }
    }

    /** The cloud has no room for more cover photos: uploads wait for the next start. Data sync is unaffected. */
    val coversFull: StateFlow<Boolean> = coverSync?.full ?: MutableStateFlow(false)

    /** (books done, books in all) while the first sign-in merge combines the libraries; null otherwise. */
    val mergeProgress: StateFlow<Pair<Int, Int>?> = firstMerge.progress

    /**
     * One signed-in run. [wake]: something changed here or on the server. [now]: retry now (connection back,
     * pull-to-refresh). [waiters]: callers of [refresh] waiting for the next pass to end.
     */
    private class Session(val owner: String) {
        val wake = Channel<Unit>(Channel.CONFLATED)
        val now = Channel<Unit>(Channel.CONFLATED)
        val waiters = MutableStateFlow<List<CompletableDeferred<Unit>>>(emptyList())
        lateinit var job: Job

        fun takeWaiters() = waiters.getAndUpdate { emptyList() }
        fun releaseWaiters() = takeWaiters().forEach { it.complete(Unit) }
    }

    @Volatile
    private var session: Session? = null

    /** Set from [signOut] until [signOutFinished]: [start] does nothing meanwhile. */
    @Volatile
    private var signingOut = false

    /** The account last [start]ed: whose unfinished merge [signOut] settles. */
    @Volatile
    private var lastOwner: String? = null

    /** Keeps a stopping session's last pass from overlapping the next session's first. */
    private val passLock = Mutex()

    /**
     * Guards [session] and [status]: [start], [stop] and [sessionEnded] swap the session under it (they're called from
     * the session observer and from the UI's threads at once, and two overlapping starts would leave a session nothing
     * can stop), and a session's worker writes the status under it only while it is the current one. Held for a few
     * field writes only: no job is started or cancelled under it (either can run code at once, which could come back
     * here), so a spin is enough. A spin that lasts fails loudly rather than hanging.
     */
    @OptIn(ExperimentalAtomicApi::class)
    private val lifecycleBusy = AtomicBoolean(false)

    @OptIn(ExperimentalAtomicApi::class)
    private inline fun <T> lifecycle(block: () -> T): T {
        var spins = 0
        var since: TimeMark? = null
        while (!lifecycleBusy.compareAndSet(false, true)) {
            if (++spins % 1024 == 0) {
                val mark = since ?: TimeSource.Monotonic.markNow().also { since = it }
                check(mark.elapsedNow() < 10.seconds) { "Library sync's lifecycle lock is held too long (a call back into it?)" }
            }
        }
        try {
            return block()
        } finally {
            lifecycleBusy.store(false)
        }
    }

    /** Sets the status from [from]'s worker, unless that session was stopped or replaced meanwhile. */
    private fun report(from: Session, status: (SyncStatus) -> SyncStatus) = lifecycle {
        if (session === from) _status.value = status(_status.value)
    }

    /** Starts syncing [owner]'s library; does nothing if it already is, or while signing out (until [signOutFinished]). */
    fun start(owner: String) {
        var replaced: Session? = null
        val next = lifecycle {
            if (signingOut) return@lifecycle null
            val current = session
            if (current != null && current.owner == owner && current.job.isActive) return@lifecycle null
            replaced = current
            val created = Session(owner)
            // Lazy: it runs only once started below, outside the lock; cancelled first (by a sign-out), it never runs.
            created.job = scope.launch(start = CoroutineStart.LAZY) {
                launch { recorder.changes.collect { created.wake.trySend(Unit) } }
                launch { listenForPings(owner, created.wake) }
                launch { keepWaitingCountFresh(created) }
                work(created)
                // The session expired: no retries until start() is called again.
                cancel()
            }
            created.job.invokeOnCompletion { created.releaseWaiters() }
            session = created
            lastOwner = owner
            _setAside.value = 0
            _status.value = SyncStatus.Syncing
            created
        }
        replaced?.end()
        if (next == null) return
        coverSync?.retryUploads()
        // A sign-out on another thread may have set its latch after the check above. Session written, then latch read
        // here; latch written, then session read (in stop) by signOut: one of the two sees the other, so this session
        // never runs past the erase. Cancelled before it starts, a lazy job never runs.
        if (signingOut) {
            lifecycle {
                if (session === next) {
                    session = null
                    _status.value = SyncStatus.Off
                }
            }
            next.end()
            return
        }
        next.job.start()
    }

    /** Stops syncing (signed out). The queue and the sync state stay as they are. */
    fun stop() {
        lifecycle { detach(SyncStatus.Off) }?.end()
    }

    /** Takes the running session off (under the lock) and shows [status]; the caller ends it, outside the lock. */
    private fun detach(status: SyncStatus): Session? {
        val current = session
        session = null
        _status.value = status
        return current
    }

    /** Cancels the session's work and releases its [refresh] waiters. */
    private fun Session.end() {
        job.cancel()
        releaseWaiters()
    }

    /**
     * The account was signed out. When the user asked for it ([signOut], or [signOutRequested] before deleting the
     * account), sync is off. Otherwise the session expired or was revoked: sync waits in [SyncStatus.SignInAgain],
     * still queueing changes, with the queue and sync state kept, and the next sign-in carries on (no new merge for the
     * same account). That includes a session that expired while the app was closed: this device had synced (a stored
     * cursor) and nobody signed out.
     */
    suspend fun sessionEnded() {
        val syncedBefore = db.state.get(SEQ_KEY) != null
        lifecycle {
            val expired = !signingOut && (_status.value != SyncStatus.Off || syncedBefore)
            detach(if (expired) SyncStatus.SignInAgain else SyncStatus.Off)
        }?.end()
    }

    /**
     * The user is about to sign out other than through [signOut] (deleting the account): the session ending isn't an
     * expiry. Lasts until [signOutFinished]; [start] does nothing meanwhile.
     */
    fun signOutRequested() {
        signingOut = true
    }

    /**
     * Signing out: sync stops, and the queue and sync state are cleared (signing in again, to any account, runs the
     * first sign-in merge). [keepLibrary] false also erases the library: books, quotes, ebooks, bookmarks, challenges,
     * share state, cover photos and ebook files. The cloud library is untouched either way.
     * From here until [signOutFinished], [start] does nothing: a start meanwhile (the session observer, the app coming
     * back to the foreground) would sync, and after Remove pull, the library being signed out of.
     */
    suspend fun signOut(keepLibrary: Boolean) {
        signingOut = true
        stop()
        val owner = lastOwner
        // After the stopped session's last pass.
        passLock.withLock {
            // Any session that slipped past the latch is stopped too, before anything is erased.
            stop()
            // An unfinished first merge: its recorded share moves would go with the sync state, leaving books under their
            // old public ids. Outside the transaction: the share state takes its own lock.
            if (keepLibrary && owner != null) {
                try {
                    firstMerge.replayShareMoves(owner)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Best effort: signing out goes on.
                }
            }
            recorder.transaction {
                db.queue.clear()
                db.state.clear()
                if (keepLibrary) return@transaction
                db.ebooks.getAllAny().forEach { db.ebooks.delete(it.bookId) }
                db.quotes.getAllAny().forEach { db.quotes.delete(it.id) }
                db.bookmarks.getAllAny().forEach { db.bookmarks.delete(it.id) }
                db.challenges.getAllAny().forEach { db.challenges.delete(it.year) }
                db.shareState?.let { shares -> shares.all().forEach { shares.delete(it.bookId) } }
                for (book in db.books.getAllAny()) {
                    db.books.delete(book.id)
                }
            }
        }
        // Every file in the folders, not just those with a record: a downloaded photo, a combined twin's ebook.
        if (!keepLibrary) coverStore?.deleteAll()
        if (!keepLibrary) ebookFiles?.deleteAll()
    }

    /** The account is signed out (or signing out failed): [start] works again. */
    fun signOutFinished() {
        signingOut = false
    }

    /** Pushes and pulls now, and returns when that pass is over (pull-to-refresh, app back in the foreground). */
    suspend fun refresh() {
        val current = session?.takeIf { it.job.isActive } ?: return
        val done = CompletableDeferred<Unit>()
        current.waiters.update { it + done }
        // Ended in the meantime: nobody would complete it.
        if (!current.job.isActive) current.releaseWaiters()
        current.now.trySend(Unit)
        done.await()
    }

    /** The network is back: retry straight away instead of waiting out the delay. */
    fun connectivityRestored() {
        session?.now?.trySend(Unit)
    }

    // ---- the worker ----

    private suspend fun work(session: Session) {
        val wake = session.wake
        val now = session.now
        var failures = 0
        while (true) {
            // This pass covers everything asked for so far.
            wake.tryReceive()
            now.tryReceive()
            val waiting = session.takeWaiters()
            val error = passLock.withLock {
                // Stopped while waiting for the lock (signing out): no pass after the erase.
                currentCoroutineContext().ensureActive()
                pass(session)
            }
            waiting.forEach { it.complete(Unit) }
            currentCoroutineContext().ensureActive()
            if (error == null) {
                failures = 0
                coverStep(session.owner)
                select {
                    wake.onReceive {}
                    now.onReceive {}
                }
                continue
            }
            if (error is SyncException && error.kind == SyncException.Kind.AUTH) {
                report(session) { SyncStatus.SignInAgain }
                return
            }
            val count = db.queue.count().first()
            report(session) {
                if (error is SyncException && error.kind == SyncException.Kind.OFFLINE) SyncStatus.Offline(count) else SyncStatus.Failed(count)
            }
            val wait = retryDelays[minOf(failures, retryDelays.lastIndex)]
            failures++
            // Local changes wait with the rest; only "retry now" cuts the wait short.
            withTimeoutOrNull(wait) { now.receive() }
        }
    }

    /** Photos after a pass: uploads, then downloads. Best effort: a failure is retried by the next pass. */
    private suspend fun coverStep(owner: String) {
        val covers = coverSync ?: return
        try {
            val books = db.books.getAllAny()
            // Downloads first: a stale file just refreshed is then marked uploaded, not sent over the newer photo.
            covers.downloadMissing(owner, books)
            covers.uploadPending(owner, books)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** One push and pull (after the first sign-in merge, the first time); the error that stopped it, or null. */
    private suspend fun pass(session: Session): Exception? {
        val owner = session.owner
        report(session) { SyncStatus.Syncing }
        return try {
            firstMerge.run(owner)
            pushQueue(api, db, applier) { n -> _setAside.update { it + n } }
            pullFrom(api, db, applier, db.state.get(SEQ_KEY)?.toLongOrNull() ?: 0L)
            currentCoroutineContext().ensureActive()
            report(session) { SyncStatus.Synced(clock()) }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
    }

    // ---- triggers ----

    /** Wakes the worker on every ping, and on every (re)subscription: pings sent while it was down are missed. */
    private suspend fun listenForPings(owner: String, wake: Channel<Unit>) {
        var failures = 0
        while (true) {
            try {
                api.pings(owner).onStart { wake.trySend(Unit) }.collect {
                    failures = 0
                    wake.trySend(Unit)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Reconnect after a wait, like a failed pass.
            }
            delay(retryDelays[minOf(failures, retryDelays.lastIndex)])
            failures++
        }
    }

    /** Keeps "N changes waiting" current while offline or failing. */
    private suspend fun keepWaitingCountFresh(session: Session) {
        db.queue.count().collect { n ->
            report(session) {
                when (it) {
                    is SyncStatus.Offline -> SyncStatus.Offline(n)
                    is SyncStatus.Failed -> SyncStatus.Failed(n)
                    else -> it
                }
            }
        }
    }
}

/**
 * Sends the queue: each entry as its row's current fields and times. An entry leaves the queue once the server has
 * applied or set aside exactly what was sent; a row edited meanwhile stays queued (its new values go next), and an entry
 * whose row is gone (removed for good) is dropped. [onSetAside] gets each batch's count of rows set aside.
 */
internal suspend fun pushQueue(api: SyncApi, db: SyncDatabase, applier: RemoteApplier, onSetAside: (Int) -> Unit) {
    while (true) {
        val batch = db.queue.batch(PUSH_BATCH)
        if (batch.isEmpty()) return
        val sent = LinkedHashMap<Pair<SyncTable, String>, SyncRow>()
        for (entry in batch) {
            val table = SyncTable.entries.firstOrNull { it.wire == entry.table } ?: continue
            applier.localRow(table, entry.rowId)?.let { sent[table to entry.rowId] = it }
        }
        val result = if (sent.isEmpty()) PushResult(emptyList(), emptyList()) else api.push(sent.values.toList())
        val done = (result.applied + result.setAside.map { it.table to it.id }).toSet()
        val removed = applier.transaction {
            val ids = batch.filter { entry ->
                val table = SyncTable.entries.firstOrNull { it.wire == entry.table } ?: return@filter true
                val key = table to entry.rowId
                val current = applier.localRow(table, entry.rowId)
                if (key in sent) key in done && current == sent[key] else current == null
            }.map { it.id }
            if (ids.isNotEmpty()) db.queue.remove(ids)
            ids
        }
        onSetAside(result.setAside.size)
        // Nothing could be confirmed (every row changed meanwhile): the change's own wake sends them again.
        if (removed.isEmpty()) return
    }
}

/** Pulls every row changed after [from], a page at a time; each page is stored with its cursor. */
internal suspend fun pullFrom(api: SyncApi, db: SyncDatabase, applier: RemoteApplier, from: Long) {
    var after = from
    while (true) {
        val page = api.pull(after, PULL_MAX)
        val last = page.last
        applier.apply(page.rows) { db.state.set(SEQ_KEY, last.toString()) }
        if (page.rows.size < PULL_MAX || last <= after) return
        after = last
    }
}
