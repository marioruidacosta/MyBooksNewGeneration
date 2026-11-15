package com.mcosta.mybooks.library

import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.sync.LibrarySync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

/**
 * Holds the active library: one open at a time. [factory] builds a library (its database, files and preferences; it is
 * opened with [LibraryHandle.open]), [delete] removes an account library's database and files. The active key is kept
 * in [deviceSettings] under `library.active`, and [start] picks it again at the next launch.
 *
 * A switch opens the new library, makes it active, stops the old one's sync, and closes the old one once no screen
 * [hold]s it. Never call [activate] or [remove] blocking the main thread: they may wait for screens to let go.
 */
class Libraries<S : LibraryHandle>(
    private val factory: suspend (LibraryId) -> S,
    private val deviceSettings: Preferences?,
    /** Where switches run, so one finishes (the old library closed) even if its caller is cancelled meanwhile. */
    private val scope: CoroutineScope,
    private val delete: suspend (LibraryId.Account) -> Unit,
) {
    private val lock = Mutex()

    @Volatile
    private lateinit var _active: MutableStateFlow<S>

    /** One entry per [hold] not released yet. */
    private val holds = MutableStateFlow<List<S>>(emptyList())

    /** Libraries switched away from, closing once no screen holds them; touched under [lock]. */
    private val closing = mutableMapOf<LibraryId, Job>()

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
            swapTo(LibraryId.Local)
        }
    }

    /**
     * Makes [id] the active library: opens it, swaps it in, stops the old one's sync, and closes the old one once no
     * screen holds it. Nothing changes if [id] is already active, or if it can't be opened (the exception is thrown).
     */
    suspend fun activate(id: LibraryId) = switching {
        if (active.value.id != id) swapTo(id)
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

    /** Runs [block] under the lock in [scope]; its failure is rethrown to the caller, never to [scope]. */
    private suspend fun <T> switching(block: suspend () -> T): T =
        scope.async { runCatching { lock.withLock { block() } } }.await().getOrThrow()

    private companion object {
        const val KEY = "library.active"
    }
}
