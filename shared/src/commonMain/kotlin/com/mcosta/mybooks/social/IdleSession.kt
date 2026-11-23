package com.mcosta.mybooks.social

import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.social.model.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How long this device's session lasts with no use. */
const val SESSION_IDLE_LIMIT_MS: Long = 7L * 24 * 60 * 60 * 1000

private const val LAST_USED = "session.last-used"
private const val EXPIRED = "session.expired"

/**
 * Each device keeps its own session for as long as it is used. After [SESSION_IDLE_LIMIT_MS] without use the session
 * ends here and on the server, and the library stays on the phone for the next sign-in.
 *
 * Only opening the app counts as use. Background work doesn't, although it keeps the token fresh: that is why the check
 * also runs at every process start and in the background check, so a phone left in a drawer doesn't stay signed in.
 */
class IdleSession(
    private val settings: Preferences?,
    private val session: StateFlow<SessionState>,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    /** Waits until the active library is open: the session can't be ended before that. */
    private val whenReady: suspend () -> Unit = {},
    /** Ends the session on the server (or, offline, here) and hides the account's library. */
    private val end: suspend () -> Unit,
) {
    private val checking = Mutex()
    private val _expired = MutableStateFlow(settings?.get(EXPIRED) == "1")

    /** The session ended for lack of use, and the user hasn't been told yet. */
    val expired: StateFlow<Boolean> = _expired.asStateFlow()

    private fun markUsed() {
        settings?.set(LAST_USED, clock().toString())
    }

    /** Never true without a recorded use (a session from before this version starts counting now) or with a clock moved back. */
    private fun isIdle(): Boolean {
        val last = settings?.get(LAST_USED)?.toLongOrNull() ?: return false
        val now = clock()
        return last <= now && now - last > SESSION_IDLE_LIMIT_MS
    }

    /** Ends the session if it has gone unused too long; true when it did. A failure is tried again next time. */
    suspend fun endIfIdle(): Boolean = checking.withLock {
        whenReady()
        if (session.first { it != SessionState.Loading } !is SessionState.SignedIn || !isIdle()) return@withLock false
        try {
            end()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withLock false
        }
        settings?.set(EXPIRED, "1")
        _expired.value = true
        true
    }

    /** The app came to the front: too late ends the session, otherwise this counts as use. */
    fun opened() {
        scope.launch { if (!endIfIdle()) markUsed() }
    }

    /** The app went to the back. Past the limit this must not count, or coming back would look recent. */
    fun left() {
        if (!isIdle()) markUsed()
    }

    /** A code is about to be verified: the new session must not be judged by the old one's last use. */
    fun signingIn() {
        markUsed()
        dismissExpired()
    }

    fun dismissExpired() {
        settings?.set(EXPIRED, "0")
        _expired.value = false
    }
}
