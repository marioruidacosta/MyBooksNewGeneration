package com.mcosta.mybooks.library

import com.mcosta.mybooks.social.model.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/** What becomes of the account's library when the user signs out ([LibrarySessions.signOut]). */
enum class OnSignOut {
    /** It stays on the phone, hidden, for the account's next sign-in. */
    KEEP,

    /** Its database and folders are deleted. */
    REMOVE,

    /** Deleting the account: its books move into the no-account library, then it's deleted (kept if that fails). */
    MOVE_TO_PHONE,
}

/**
 * Switches libraries with the session.
 * - An account signing in gets its own library on this phone (an empty one the first time), and that library syncs.
 *   Before its sync starts, [beforeSync] may wait (the first sign-in's question); that wait is cancelled as soon as the
 *   session changes (signed out, expired, another account), while the session goes on being followed.
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

    /** The wait before [space]'s sync starts, and the start ([signedIn]); touched by [follow] only. */
    private class Holding(val space: LibraryHandle, val job: Job)

    private var holding: Holding? = null

    /**
     * Follows the session for good. Call once, after [Libraries.openActive]. An event that fails (a library that can't
     * be read) is dropped; the next one is still followed.
     */
    suspend fun follow() {
        session.collect { state ->
            try {
                when (state) {
                    is SessionState.SignedIn -> signedIn(state.userId)
                    SessionState.SignedOut -> {
                        // Nothing more starts for the account that left (and its question goes away).
                        holding?.job?.cancel()
                        libraries.clearOpenFailure()
                        // Asked for: [signOut] switches. Otherwise the account's library waits for its account to sign in again.
                        libraries.active.value.sync?.sessionEnded()
                    }
                    SessionState.Loading -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nothing more to do for this event.
            }
        }
    }

    private suspend fun signedIn(userId: String) {
        val id = try {
            LibraryId.Account(userId)
        } catch (_: IllegalArgumentException) {
            return
        }
        val current = libraries.active.value
        val waiting = holding
        // The same account again (its session refreshed): its library is already waiting, or starting.
        if (current.id == id && waiting != null && waiting.space === current && waiting.job.isActive) return
        waiting?.job?.cancel()
        if (current.id != id) {
            // Another account's library: its sync must not go on with this account's session, even for a moment.
            current.sync?.stop()
            try {
                libraries.activate(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Its library can't be opened: the no-account library is shown (it never syncs); nothing is deleted.
                libraries.activate(LibraryId.Local)
                return
            }
        }
        val space = libraries.active.value
        if (space.id != id) return
        // Not waited for here: the session's next change must get through (and cancel it) at once.
        holding = Holding(space, scope.launch { startSync(space, userId) })
    }

    private suspend fun startSync(space: S, userId: String) {
        try {
            beforeSync(space)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The question couldn't be asked (a library couldn't be read): sync starts all the same; it's asked next time.
        }
        // While it waited, the user may have signed out, or another account signed in: then nothing starts.
        if (libraries.active.value !== space || (session.value as? SessionState.SignedIn)?.userId != userId) return
        started = space
        space.sync?.start(userId)
    }

    /** [signOut] with "Keep" ([keep]) or "Remove". */
    suspend fun signOut(keep: Boolean, signOutOfServer: suspend () -> Unit) =
        signOut(if (keep) OnSignOut.KEEP else OnSignOut.REMOVE, signOutOfServer)

    /**
     * Signing out, as the user asked: the account's sync stops, with its queue and sync state kept in its library;
     * [signOutOfServer] ends the session; the no-account library is shown; then the account's library is kept, deleted
     * (database and folders, nothing else), or, for a deleted account, has its books moved into the no-account library
     * and is deleted ([after]). A move that fails keeps the account's library (nothing is lost; the move is retried at
     * the next start, [Libraries.retryMovesToPhone]) and doesn't fail the sign-out. If [signOutOfServer] fails, the account stays signed in and its library active (its sync starts again
     * with [syncNow]).
     */
    suspend fun signOut(after: OnSignOut, signOutOfServer: suspend () -> Unit) {
        scope.async { runCatching { signingOut(after, signOutOfServer) } }.await().getOrThrow()
    }

    private suspend fun signingOut(after: OnSignOut, signOutOfServer: suspend () -> Unit) {
        val space = libraries.active.value
        val id = space.id
        val sync = space.sync
        try {
            sync?.signOut()
            signOutOfServer()
            started = null
            libraries.clearOpenFailure()
            libraries.activate(LibraryId.Local)
            if (id is LibraryId.Account) {
                when (after) {
                    OnSignOut.KEEP -> Unit
                    OnSignOut.REMOVE -> libraries.remove(id)
                    OnSignOut.MOVE_TO_PHONE -> try {
                        libraries.moveToPhoneAndRemove(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Its library stays on the phone, books and all, and the move is tried again at the next start.
                    }
                }
            }
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
