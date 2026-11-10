package com.mcosta.mybooks.library

import com.mcosta.mybooks.data.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** An open library, as [Libraries] sees it: [LibrarySpace] in the app, a fake in tests. */
interface LibraryHandle {
    val id: LibraryId

    /** Stops its work and closes its database; it isn't used again. */
    suspend fun close()
}

/**
 * Holds the active library: one open at a time. [factory] opens a library (its database, files and preferences),
 * [delete] removes an account library's database and files. The active key is kept in [deviceSettings] under
 * `library.active`, and [start] opens it again at the next launch.
 */
class Libraries<S : LibraryHandle>(
    private val factory: suspend (LibraryId) -> S,
    private val deviceSettings: Preferences?,
    /** Where switches run, so one finishes (the old library closed) even if its caller is cancelled meanwhile. */
    private val scope: CoroutineScope,
    private val delete: suspend (LibraryId.Account) -> Unit,
) {
    private val lock = Mutex()
    private lateinit var _active: MutableStateFlow<S>

    /** The open library. Available once [start] has returned. */
    val active: StateFlow<S>
        get() {
            check(::_active.isInitialized) { "Libraries not started" }
            return _active
        }

    /**
     * Opens the library active at the last launch (the no-account one if none, or if it can't be opened; nothing is
     * deleted then). Call once, before anything uses [active].
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
     * Makes [id] the active library: opens it, swaps it in, then closes the old one. Nothing changes if [id] is
     * already active, or if it can't be opened (the exception is thrown).
     */
    suspend fun activate(id: LibraryId) = switching {
        val old = active.value
        if (old.id == id) return@switching
        val next = factory(id)
        _active.value = next
        deviceSettings?.set(KEY, id.key)
        try {
            old.close()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The switch is done; a library that failed to close is left as it is.
        }
    }

    /** Deletes an account library's database and files. Never the no-account library, nor the active one. */
    suspend fun remove(id: LibraryId) {
        require(id is LibraryId.Account) { "The no-account library is never removed" }
        switching {
            require(id != active.value.id) { "The active library can't be removed" }
            delete(id)
        }
    }

    /** Runs [block] under the lock in [scope]; its failure is rethrown to the caller, never to [scope]. */
    private suspend fun <T> switching(block: suspend () -> T): T =
        scope.async { runCatching { lock.withLock { block() } } }.await().getOrThrow()

    private companion object {
        const val KEY = "library.active"
    }
}
