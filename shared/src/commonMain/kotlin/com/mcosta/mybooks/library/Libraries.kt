package com.mcosta.mybooks.library

import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.sync.LibrarySync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/** An open library, as [Libraries] sees it: [LibrarySpace] in the app, a fake in tests. */
interface LibraryHandle {
    val id: LibraryId

    /** Its sync engine: null for the no-account library (it never syncs), and without the community. */
    val sync: LibrarySync? get() = null

    /** Its tables and folders, for moving books between libraries ([LibraryTransfer]); null where there are none. */
    val contents: LibraryContents? get() = null

    /** Loads what it reads before use (its preferences); called once, off the main thread, before its sync starts. */
    suspend fun open() {}

    /** Stops its work and closes its database; it isn't used again. */
    suspend fun close()
}

/** A screen's hold on a library ([Libraries.hold]): the library isn't closed while it's held. */
class LibraryHold internal constructor(private val onRelease: () -> Unit) {
    private val released = MutableStateFlow(false)

    /** The screen is gone; releasing twice does nothing. */
    fun release() {
        if (released.compareAndSet(expect = false, update = true)) onRelease()
    }
}

/** "Add this phone's [count] books to your account?", asked at [account]'s first sign-in on this phone. */
data class AddBooksOffer(val account: LibraryId.Account, val count: Int)

/**
 * Holds the active library: one open at a time. [factory] builds a library (its database, files and preferences; it is
 * opened with [LibraryHandle.open]), [delete] removes an account library's database and files. The active key is kept
 * in [deviceSettings] under `library.active`, and [start] picks it again at the next launch.
 *
 * A switch opens the new library, makes it active, stops the old one's sync, and closes the old one once no screen
 * [hold]s it. Never call [activate] or [remove] blocking the main thread: they may wait for screens to let go.
 *
 * The phone's own books (the no-account library's) can move into the active account's library: asked at the account's
 * first sign-in ([offerPhoneBooks]), or later from Settings ([addPhoneBooks]). Deleting the account moves its books the
 * other way ([moveToPhoneAndRemove]). An inactive library is opened for that under the lock, never as a second copy.
 */
class Libraries<S : LibraryHandle>(
    private val factory: suspend (LibraryId) -> S,
    private val deviceSettings: Preferences?,
    /** Where switches run, so one finishes (the old library closed) even if its caller is cancelled meanwhile. */
    private val scope: CoroutineScope,
    private val delete: suspend (LibraryId.Account) -> Unit,
    private val transfer: LibraryTransfer = LibraryTransfer(),
) {
    private val lock = Mutex()

    @Volatile
    private lateinit var _active: MutableStateFlow<S>

    /** One entry per [hold] not released yet. */
    private val holds = MutableStateFlow<List<S>>(emptyList())

    /** Libraries switched away from, closing once no screen holds them; touched under [lock]. */
    private val closing = mutableMapOf<LibraryId, Job>()

    private val _openFailed = MutableStateFlow<LibraryId.Account?>(null)

    /**
     * The account whose library couldn't be opened (at start, or when it was activated): the no-account library is
     * shown instead, and nothing is deleted. Cleared when that account's library is next activated, or by
     * [clearOpenFailure] (sign-out).
     */
    val openFailed: StateFlow<LibraryId.Account?> = _openFailed.asStateFlow()

    /** The failure is over (signed out, or the session ended): there is nothing more to say about it. */
    fun clearOpenFailure() {
        _openFailed.value = null
    }

    /** The active library. Available once [start] has returned. */
    val active: StateFlow<S>
        get() {
            check(::_active.isInitialized) { "Libraries not started" }
            return _active
        }

    /**
     * Picks the library active at the last launch (the no-account one if none, or if it can't be built; nothing is
     * deleted then), without opening it: [openActive] does, off the main thread. Call once, before anything uses
     * [active].
     */
    suspend fun start() = lock.withLock {
        check(!::_active.isInitialized) { "Libraries already started" }
        val last = deviceSettings?.get(KEY)?.let { key ->
            try {
                libraryIdOf(key)
            } catch (_: IllegalArgumentException) {
                null
            }
        } ?: LibraryId.Local
        val space = if (last == LibraryId.Local) {
            factory(LibraryId.Local)
        } else {
            try {
                factory(last)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _openFailed.value = last as? LibraryId.Account
                factory(LibraryId.Local)
            }
        }
        _active = MutableStateFlow(space)
    }

    /**
     * Opens the library [start] picked (its preferences load here). If it can't be opened, the no-account library is
     * made active instead; nothing is deleted. Call once, after [start], before its sync starts.
     */
    suspend fun openActive() = switching {
        val space = active.value
        try {
            space.open()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (space.id == LibraryId.Local) throw e
            _openFailed.value = space.id as? LibraryId.Account
            swapTo(LibraryId.Local)
        }
    }

    /**
     * Makes [id] the active library: opens it, swaps it in, stops the old one's sync, and closes the old one once no
     * screen holds it. Nothing changes if [id] is already active, or if it can't be opened (the exception is thrown).
     */
    suspend fun activate(id: LibraryId) = switching {
        if (active.value.id != id) {
            try {
                swapTo(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (id is LibraryId.Account) _openFailed.value = id
                throw e
            }
        }
        _openFailed.update { if (it == id) null else it }
    }

    /** Deletes an account library's database and files, once it's closed. Never the no-account library, nor the active one. */
    suspend fun remove(id: LibraryId) {
        require(id is LibraryId.Account) { "The no-account library is never removed" }
        switching {
            require(id != active.value.id) { "The active library can't be removed" }
            closing[id]?.join()
            delete(id)
        }
    }

    /**
     * Marks [space] as shown by a screen: it isn't closed until the hold is released. Null when [space] is no longer
     * active (a switch got there first): the screen is about to show the new one instead.
     */
    fun hold(space: S): LibraryHold? {
        holds.update { it + space }
        // After adding the hold: a switch either sees it (and waits for it) or has already swapped (seen here).
        if (_active.value !== space) {
            unhold(space)
            return null
        }
        return LibraryHold { unhold(space) }
    }

    private fun unhold(space: S) = holds.update { list ->
        val at = list.indexOfFirst { it === space }
        if (at < 0) list else list.toMutableList().apply { removeAt(at) }
    }

    /** Under [lock]: opens [id], makes it active, then stops the old one's sync and closes it when no screen holds it. */
    private suspend fun swapTo(id: LibraryId) {
        val old = active.value
        // One open copy of a database at a time: a copy still closing finishes first.
        closing[id]?.join()
        val next = factory(id)
        try {
            next.open()
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { next.close() } }
            throw e
        }
        _active.value = next
        deviceSettings?.set(KEY, id.key)
        // Nothing more syncs into the library left behind.
        old.sync?.stop()
        // Not joined here: screens let go of it after they have recomposed with the new library.
        closing[old.id] = scope.launch {
            holds.first { list -> list.none { it === old } }
            try {
                old.close()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The switch is done; a library that failed to close is left as it is.
            }
        }
    }

    // ---- the phone's books ----

    /** The open question, for [space]; [answer] completes with true for "Add them". */
    private class Asking(val space: LibraryHandle, val offer: AddBooksOffer, val answer: CompletableDeferred<Boolean>)

    private val asking = MutableStateFlow<Asking?>(null)
    private val _offer = MutableStateFlow<AddBooksOffer?>(null)

    /** "Add this phone's N books to your account?" while it waits for its answer; null otherwise. */
    val offer: StateFlow<AddBooksOffer?> = _offer.asStateFlow()

    /**
     * Before [space]'s sync starts at its account's sign-in: if the question was never answered in [space] and the
     * no-account library has live books, asks it ([offer]) and returns once it's answered ([addPhoneBooks] or [notNow]),
     * the answer stored in [space] so it's never asked again there. Cancelled (the session changed meanwhile), the
     * question goes away unanswered, to be asked at the next sign-in.
     */
    suspend fun offerPhoneBooks(space: S) {
        val account = space.id as? LibraryId.Account ?: return
        val contents = space.contents ?: return
        if (contents.db.state.get(ASKED_LOCAL_BOOKS_KEY) != null) return
        val count = phoneBooks()
        if (count == 0) return
        val asked = Asking(space, AddBooksOffer(account, count), CompletableDeferred())
        asking.value = asked
        _offer.value = asked.offer
        try {
            // "Add them" stores its answer with the books it moved.
            if (!asked.answer.await()) switching { if (active.value === space) contents.db.state.set(ASKED_LOCAL_BOOKS_KEY, NOT_NOW) }
        } finally {
            withdraw(asked)
        }
    }

    private val _moving = MutableStateFlow(false)

    /** True while the phone's books are moving into an account's library ([addPhoneBooks]): "Moving your books…". */
    val moving: StateFlow<Boolean> = _moving.asStateFlow()

    /**
     * "Add them": moves the no-account library's books into [account]'s library (see [LibraryTransfer]), and answers
     * [account]'s question if it's open. Also Settings → Account → "Add this phone's books". Nothing happens unless
     * [account]'s library is the active one, and the open question (if any) is [account]'s: a tap meant for another
     * account never moves anything. Returns once they're moved; if moving fails, the exception is thrown, the question
     * stays open and nothing is lost (it can be repeated).
     */
    suspend fun addPhoneBooks(account: LibraryId.Account) {
        val asked = asking.value
        val added = switching {
            val target = active.value
            val to = target.contents
            if (target.id != account || to == null) return@switching false
            // The open question is another library's: this tap is stale.
            if (asked != null && (asked.offer.account != account || asked.space !== target)) return@switching false
            _moving.value = true
            try {
                withLibrary(LibraryId.Local) { phone -> phone.contents?.let { transfer.move(it, to) } }
                to.db.state.set(ASKED_LOCAL_BOOKS_KEY, ADDED)
            } finally {
                _moving.value = false
            }
            true
        }
        if (added && asked != null) {
            withdraw(asked)
            asked.answer.complete(true)
        }
    }

    /** "Not now": the books stay in the no-account library, and the account's library syncs without them. */
    fun notNow() {
        val asked = asking.value ?: return
        withdraw(asked)
        asked.answer.complete(false)
    }

    private fun withdraw(asked: Asking) {
        asking.compareAndSet(asked, null)
        _offer.update { if (it === asked.offer) null else it }
    }

    /**
     * How many live books the no-account library holds, while an account's library is active (there's nowhere to add
     * them otherwise: 0).
     */
    suspend fun phoneBooks(): Int = switching {
        if (active.value.id !is LibraryId.Account) 0
        else withLibrary(LibraryId.Local) { it.contents?.db?.books?.getAll()?.size ?: 0 }
    }

    /**
     * Deleting the account [id] (not the active library): [id]'s library (closed first) moves its books into the
     * no-account library, then is removed. The move is noted in the device settings first, and the note goes once [id]
     * is removed: if the move fails, [id]'s library stays as it is (nothing is lost), the exception is thrown, and
     * [retryMovesToPhone] tries again at the next start.
     */
    suspend fun moveToPhoneAndRemove(id: LibraryId.Account) = switching {
        require(id != active.value.id) { "The active library can't be removed" }
        setPendingMoves(pendingMoves() + id)
        movingToPhone(id)
    }

    /**
     * At start, off the main thread (after [openActive]): the moves of deleted accounts' books that failed
     * ([moveToPhoneAndRemove]) are tried again. One that fails again is tried at the next start.
     */
    suspend fun retryMovesToPhone() {
        for (id in pendingMoves()) {
            try {
                switching { if (id != active.value.id) movingToPhone(id) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Still noted: the next start tries again.
            }
        }
    }

    /** Under [lock]: [id]'s books into the no-account library (open or not), then [id] removed and its note cleared. */
    private suspend fun movingToPhone(id: LibraryId.Account) {
        withLibrary(LibraryId.Local) { phone ->
            val to = checkNotNull(phone.contents) { "The no-account library has no tables" }
            withLibrary(id) { account -> transfer.move(checkNotNull(account.contents) { "The account library has no tables" }, to) }
        }
        delete(id)
        setPendingMoves(pendingMoves() - id)
    }

    /** The accounts whose books still have to move into the no-account library (device settings, [PENDING_MOVES]). */
    private fun pendingMoves(): List<LibraryId.Account> =
        deviceSettings?.get(PENDING_MOVES).orEmpty().split(',').filter { it.isNotBlank() }.mapNotNull { key ->
            try {
                libraryIdOf(key) as? LibraryId.Account
            } catch (_: IllegalArgumentException) {
                null
            }
        }.distinct()

    private fun setPendingMoves(ids: List<LibraryId.Account>) {
        deviceSettings?.set(PENDING_MOVES, ids.distinct().joinToString(",") { it.key })
    }

    /**
     * Under [lock]: runs [block] on library [id], the active one or else a copy opened for it and closed after. One open
     * copy of a database at a time: a copy still closing finishes first.
     */
    private suspend fun <T> withLibrary(id: LibraryId, block: suspend (S) -> T): T {
        val current = active.value
        if (current.id == id) return block(current)
        closing[id]?.join()
        val space = factory(id)
        try {
            space.open()
            return block(space)
        } finally {
            withContext(NonCancellable) { runCatching { space.close() } }
        }
    }

    /** Runs [block] under the lock in [scope]; its failure is rethrown to the caller, never to [scope]. */
    private suspend fun <T> switching(block: suspend () -> T): T =
        scope.async { runCatching { lock.withLock { block() } } }.await().getOrThrow()

    private companion object {
        const val KEY = "library.active"

        /** Deleted accounts whose library still has to move into the no-account library (comma-separated ids). */
        const val PENDING_MOVES = "library.pendingMove"

        /** The stored answers ([ASKED_LOCAL_BOOKS_KEY]). */
        const val ADDED = "added"
        const val NOT_NOW = "not-now"
    }
}
