package com.mcosta.mybooks.social

import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.social.model.BookSuggestion
import com.mcosta.mybooks.social.model.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** A notification to show; [lastId] is the newest suggestion it covers. */
data class SuggestionAlert(val title: String, val text: String, val owner: String, val lastId: Long)

/**
 * The background check for new book suggestions (there are no push notifications): which ones the person hasn't seen
 * and hasn't been told about yet. On by default; kept per device, and the place reached per account.
 */
class SuggestionAlerts(private val social: SocialServices, private val prefs: Preferences?) {
    val enabled: Boolean get() = prefs?.get(ENABLED) != OFF

    fun setEnabled(on: Boolean) {
        prefs?.set(ENABLED, if (on) ON else OFF)
    }

    /** Whether the background check has someone to check for: signed in, with the notification on. */
    val shouldRun: Flow<Boolean> = social.auth.session
        .filter { it !is SessionState.Loading }
        .map { enabled && it is SessionState.SignedIn }

    /** Someone is no longer signed in here (or another account is): their notification must go. */
    val accountEnded: Flow<Unit> = flow {
        var last: String? = null
        social.auth.session.filter { it !is SessionState.Loading }.collect { session ->
            val now = (session as? SessionState.SignedIn)?.userId
            if (now == null || (last != null && now != last)) emit(Unit)
            last = now
        }
    }

    /** Whether [alert]'s account is still the one signed in: a friend's note isn't shown to the next person. */
    fun isCurrent(alert: SuggestionAlert): Boolean =
        (social.auth.session.value as? SessionState.SignedIn)?.userId == alert.owner

    /** Whether permission to notify was already asked for, the first time a suggestion arrived. */
    val askedToNotify: Boolean get() = prefs?.get(ASKED) != null

    fun markAskedToNotify() {
        prefs?.set(ASKED, ON)
    }

    /** What to notify, or null when there's nothing new. A network failure is thrown, so the work is tried again. */
    suspend fun check(): SuggestionAlert? {
        if (!enabled) return null
        val session = withTimeoutOrNull(SESSION_WAIT) { social.auth.session.first { it !is SessionState.Loading } }
        val owner = (session as? SessionState.SignedIn)?.userId ?: return null
        val told = prefs?.get(lastKey(owner))?.toLongOrNull() ?: 0L
        val new = social.activity.bookSuggestions().filter { !it.seen && it.id > told }
        if (new.isEmpty()) return null
        return alertFor(new, owner)
    }

    /** [alert] was shown: its suggestions aren't notified again. */
    fun notified(alert: SuggestionAlert) {
        prefs?.set(lastKey(alert.owner), alert.lastId.toString())
    }

    private fun alertFor(new: List<BookSuggestion>, owner: String): SuggestionAlert {
        val lastId = new.maxOf { it.id }
        val only = new.singleOrNull()
        if (only != null) {
            val note = only.note.trim()
            val text = if (note.isEmpty()) only.book.title else "${only.book.title}: “$note”"
            return SuggestionAlert("${only.sender.displayName} suggested a book", text, owner, lastId)
        }
        val names = new.map { it.sender.displayName }.distinct()
        val from = when (names.size) {
            1 -> names[0]
            2 -> "${names[0]} and ${names[1]}"
            else -> "${names[0]}, ${names[1]} and ${names.size - 2} more"
        }
        return SuggestionAlert("${new.size} new book suggestions", "From $from", owner, lastId)
    }

    private fun lastKey(owner: String) = "$LAST_PREFIX$owner"

    private companion object {
        const val ENABLED = "bookSuggestions.notify"
        const val ASKED = "bookSuggestions.askedToNotify"
        const val LAST_PREFIX = "bookSuggestions.lastNotified."
        const val ON = "on"
        const val OFF = "off"

        /** How long a background run waits for the stored sign-in to be restored. */
        val SESSION_WAIT = 20.seconds
    }
}
