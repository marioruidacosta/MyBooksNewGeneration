package com.mcosta.mybooks.sync

import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.ebooks.EbookFiles
import com.mcosta.mybooks.domain.EbookFormat
import com.mcosta.mybooks.share.ShareTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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

    private val firstMerge = FirstMerge(api, db, applier, shares, coverStore, ebookFiles) { n -> _setAside.update { it + n } }

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

    /** Keeps a stopping session's last pass from overlapping the next session's first. */
    private val passLock = Mutex()

    /** Starts syncing [owner]'s library; does nothing if it already is. */
    fun start(owner: String) {
        val current = session
        if (current != null && current.owner == owner && current.job.isActive) return
        stop()
        val next = Session(owner)
        _setAside.value = 0
        _status.value = SyncStatus.Syncing
        next.job = scope.launch {
            launch { recorder.changes.collect { next.wake.trySend(Unit) } }
            launch { listenForPings(owner, next.wake) }
            launch { keepWaitingCountFresh() }
            work(next)
            // The session expired: no retries until start() is called again.
            cancel()
        }
        next.job.invokeOnCompletion { next.releaseWaiters() }
        session = next
    }

    /** Stops syncing (signed out). The queue and the sync state stay as they are. */
    fun stop() {
        val current = session
        session = null
        current?.job?.cancel()
        current?.releaseWaiters()
        _status.value = SyncStatus.Off
    }

    /**
     * Signing out: sync stops, and the queue and sync state are cleared (signing in again, to any account, runs the
     * first sign-in merge). [keepLibrary] false also erases the library: books, quotes, ebooks, bookmarks, challenges,
     * share state, cover photos and ebook files. The cloud library is untouched either way.
     */
    suspend fun signOut(keepLibrary: Boolean) {
        stop()
        val photos = mutableListOf<String>()
        val files = mutableListOf<Pair<Long, EbookFormat>>()
        // After the stopped session's last pass.
        passLock.withLock {
            recorder.transaction {
                db.queue.clear()
                db.state.clear()
                if (keepLibrary) return@transaction
                for (ebook in db.ebooks.getAllAny()) {
                    files += ebook.bookId to ebook.format
                    db.ebooks.delete(ebook.bookId)
                }
                db.quotes.getAllAny().forEach { db.quotes.delete(it.id) }
                db.bookmarks.getAllAny().forEach { db.bookmarks.delete(it.id) }
                db.challenges.getAllAny().forEach { db.challenges.delete(it.year) }
                db.shareState?.let { shares -> shares.all().forEach { shares.delete(it.bookId) } }
                for (book in db.books.getAllAny()) {
                    book.coverUrl?.takeIf { coverStore?.isLocal(it) == true }?.let { photos += it }
                    db.books.delete(book.id)
                }
            }
        }
        photos.forEach { coverStore?.delete(it) }
        files.forEach { (book, format) -> ebookFiles?.delete(book, format) }
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
            val error = passLock.withLock { pass(session.owner) }
            waiting.forEach { it.complete(Unit) }
            currentCoroutineContext().ensureActive()
            if (error == null) {
                failures = 0
                select {
                    wake.onReceive {}
                    now.onReceive {}
                }
                continue
            }
            if (error is SyncException && error.kind == SyncException.Kind.AUTH) {
                _status.value = SyncStatus.SignInAgain
                return
            }
            val count = db.queue.count().first()
            _status.value =
                if (error is SyncException && error.kind == SyncException.Kind.OFFLINE) SyncStatus.Offline(count) else SyncStatus.Failed(count)
            val wait = retryDelays[minOf(failures, retryDelays.lastIndex)]
            failures++
            // Local changes wait with the rest; only "retry now" cuts the wait short.
            withTimeoutOrNull(wait) { now.receive() }
        }
    }

    /** One push and pull (after the first sign-in merge, the first time); the error that stopped it, or null. */
    private suspend fun pass(owner: String): Exception? {
        _status.value = SyncStatus.Syncing
        return try {
            firstMerge.run(owner)
            pushQueue(api, db, applier) { n -> _setAside.update { it + n } }
            pullFrom(api, db, applier, db.state.get(SEQ_KEY)?.toLongOrNull() ?: 0L)
            currentCoroutineContext().ensureActive()
            _status.value = SyncStatus.Synced(clock())
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
    private suspend fun keepWaitingCountFresh() {
        db.queue.count().collect { n ->
            _status.update {
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
