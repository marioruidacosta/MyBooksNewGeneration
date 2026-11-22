package com.mcosta.mybooks.social

import com.mcosta.mybooks.social.model.BookSuggestion
import com.mcosta.mybooks.social.model.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The books friends suggested to the signed-in person, and how many are new (the badge on Community). */
class SuggestionInbox(private val social: SocialServices, private val scope: CoroutineScope) {
    private val _items = MutableStateFlow<List<BookSuggestion>>(emptyList())
    val items: StateFlow<List<BookSuggestion>> = _items.asStateFlow()

    val unseen: StateFlow<Int> = _items.map { list -> list.count { !it.seen } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    /** Whose suggestions are held: an answer for anyone else is dropped. */
    private var owner: String? = null

    /** Reads are numbered: an older answer arriving after a newer one is dropped. */
    private var asked = 0
    private var answered = 0

    /** Closed here, or being closed: a read that started earlier may still list them. */
    private val closed = mutableSetOf<Long>()

    /** Marked seen here: a read that started earlier may still call them new. */
    private val markedSeen = mutableSetOf<Long>()

    init {
        scope.launch {
            social.auth.session.collect { session ->
                when (session) {
                    is SessionState.SignedIn -> {
                        if (session.userId != owner) {
                            owner = session.userId
                            forget()
                        }
                        refresh()
                    }
                    SessionState.SignedOut -> {
                        owner = null
                        forget()
                    }
                    SessionState.Loading -> Unit
                }
            }
        }
    }

    private fun forget() {
        closed.clear()
        markedSeen.clear()
        _items.value = emptyList()
    }

    /** [list] with what was done here applied, whatever the server had when it was read. */
    private fun shown(list: List<BookSuggestion>) = list
        .filterNot { it.id in closed }
        .map { if (it.id in markedSeen && !it.seen) it.copy(seen = true) else it }

    /** Reads them again; when that fails, what's shown stays. */
    suspend fun refresh() {
        val who = owner ?: return
        val ask = ++asked
        val found = try {
            social.activity.bookSuggestions()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        if (owner != who || ask < answered) return
        answered = ask
        _items.value = shown(found)
    }

    /** The person is looking at them: the badge clears here at once, and on their other devices. Only what's on screen. */
    fun markSeen() {
        val ids = _items.value.filterNot { it.seen }.map { it.id }
        if (ids.isEmpty()) return
        markedSeen += ids
        _items.update(::shown)
        scope.launch {
            try {
                social.activity.markSuggestionsSeen(ids)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Still new on the server: the badge comes back at the next refresh.
                markedSeen -= ids.toSet()
            }
        }
    }

    /** The book was added or dismissed. False when the server couldn't be told: it is shown again. */
    suspend fun close(id: Long): Boolean {
        val closing = _items.value.firstOrNull { it.id == id } ?: return true
        val who = owner
        closed += id
        _items.update(::shown)
        return try {
            social.activity.closeSuggestion(id)
            true
        } catch (e: CancellationException) {
            closed -= id
            throw e
        } catch (_: Exception) {
            if (owner == who) {
                closed -= id
                _items.update { list ->
                    (list.filterNot { it.id == id } + closing)
                        .sortedWith(compareByDescending<BookSuggestion> { it.createdAt }.thenByDescending { it.id })
                }
            }
            false
        }
    }
}
