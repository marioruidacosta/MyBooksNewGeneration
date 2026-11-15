package com.mcosta.mybooks.library

import com.mcosta.mybooks.social.model.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile

/**
 * Switches libraries with the session.
 * - An account signing in gets its own library on this phone (an empty one the first time), and that library syncs.
 * - A sign-out the user asked for ([signOut]) shows the no-account library; "Remove" then deletes the account's.
 * - A session that ends otherwise (expired, revoked) leaves the account's library active, waiting in
 *   [com.mcosta.mybooks.sync.SyncStatus.SignInAgain] with its changes queued.
 *
 * A library's sync is only ever started here, for the account the library belongs to, once it is active and open.
 */
class LibrarySessions<S : LibraryHandle>(
    private val libraries: Libraries<S>,
    private val session: StateFlow<SessionState>,
    /** Where sign-outs run, so one finishes even if the screen that asked is closed by the switch it makes. */
    private val scope: CoroutineScope,
    /**
     * Runs once an account's library is active and open, before its sync starts: the first sign-in on this phone may
     * wait here (the offer to add the phone's books).
     */
    private val beforeSync: suspend (S) -> Unit = {},
) {
    /** The library whose sync was started for the signed-in account: the only one [syncNow] starts again. */
    @Volatile
    private var started: S? = null

    /** Follows the session for good. Call once, after [Libraries.openActive]. */
    suspend fun follow() {
        session.collect { state ->
            when (state) {
                is SessionState.SignedIn -> signedIn(state.userId)
                // Asked for: [signOut] switches. Otherwise the account's library waits for its account to sign in again.
                SessionState.SignedOut -> libraries.active.value.sync?.sessionEnded()
                SessionState.Loading -> Unit
            }
        }
    }

    private suspend fun signedIn(userId: String) {
        val id = try {
            LibraryId.Account(userId)
        } catch (_: IllegalArgumentException) {
            return
        }
        if (libraries.active.value.id != id) {
            try {
                libraries.activate(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Its library can't be opened: the no-account library is shown (it never syncs); nothing is deleted.
                try {
                    libraries.activate(LibraryId.Local)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                return
            }
        }
        val space = libraries.active.value
        if (space.id != id) return
        beforeSync(space)
        started = space
        space.sync?.start(userId)
    }

    /**
     * Signing out, as the user asked ("Keep" or "Remove"): the account's sync stops, with its queue and sync state kept
     * in its library; [signOutOfServer] ends the session; the no-account library is shown; and with [keep] false the
     * account's library (database and folders) is deleted, nothing else. If [signOutOfServer] fails, the account stays
     * signed in and its library active (its sync starts again with [syncNow]).
     */
    suspend fun signOut(keep: Boolean, signOutOfServer: suspend () -> Unit) {
        scope.async { runCatching { signingOut(keep, signOutOfServer) } }.await().getOrThrow()
    }

    private suspend fun signingOut(keep: Boolean, signOutOfServer: suspend () -> Unit) {
        val space = libraries.active.value
        val id = space.id
        val sync = space.sync
        try {
            sync?.signOut()
            signOutOfServer()
            started = null
            libraries.activate(LibraryId.Local)
            if (!keep && id is LibraryId.Account) libraries.remove(id)
        } finally {
            sync?.signOutFinished()
        }
    }

    /**
     * Pushes and pulls now, and returns when that's done. A signed-in account whose sync stopped (the session had
     * expired, or signing out failed) gets it started again, but only in its own library, once [follow] started it.
     */
    suspend fun syncNow() {
        val space = libraries.active.value
        val sync = space.sync ?: return
        val userId = (session.value as? SessionState.SignedIn)?.userId
        if (userId != null && space === started && space.id.key == userId) sync.start(userId)
        sync.refresh()
    }
}
