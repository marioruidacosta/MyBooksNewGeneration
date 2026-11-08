package com.mcosta.mybooks.ui.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.social.SocialServices
import com.mcosta.mybooks.social.model.FeedBook
import com.mcosta.mybooks.social.model.FeedCursor
import com.mcosta.mybooks.social.model.FeedEntry
import com.mcosta.mybooks.social.model.FeedScope
import com.mcosta.mybooks.social.model.FriendReading
import com.mcosta.mybooks.social.model.Person
import com.mcosta.mybooks.social.model.ReportReason
import com.mcosta.mybooks.social.model.SessionState
import com.mcosta.mybooks.social.socialMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

data class ActivityState(
    /** Signed in with a profile; otherwise the screen invites the user to join. */
    val signedIn: Boolean = false,
    /** The signed-in user (their own entries have no Report). */
    val myId: String? = null,
    val scope: FeedScope = FeedScope.FRIENDS,
    val entries: List<FeedEntry> = emptyList(),
    /** Where the next page starts; null at the end. */
    val next: FeedCursor? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    /** False when the user has no friends yet (Friends tab): the screen offers "Find people". */
    val hasFriends: Boolean = true,
    /** Loading the feed failed: shown with Retry. */
    val error: String? = null,
    /** A one-off message (snackbar). */
    val message: String? = null,
    /** Non-null while the "who liked this" sheet is open. */
    val likers: List<Person>? = null,
    /** The book whose sheet is open. */
    val openBook: FeedBook? = null,
    val openBookInLibrary: Boolean = false,
    /** "Friends are reading" (Friends tab only; empty when none or when it couldn't be loaded). */
    val reading: List<FriendReading> = emptyList(),
    /** The user's shelf for each shown book that is in their library, by the book's share id. */
    val shelves: Map<String, ReadingStatus> = emptyMap(),
) {
    /** The entries as cards: books added at once grouped. */
    val items: List<FeedItem> get() = groupFeed(entries)
}

/** The Activity screen: friends' or everyone's feed, likes, reports and "Add to my books". */
class ActivityViewModel(
    private val services: SocialServices,
    private val repository: BookRepository,
    /** Where the library is matched against the shown books (thousands of books: off the main thread). */
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(ActivityState())
    val state: StateFlow<ActivityState> = _state.asStateFlow()
    private val shelf = LibraryShelf(repository)
    private val likes = LikeToggler(services.activity, viewModelScope)
    private val adding = Mutex()

    init {
        viewModelScope.launch {
            services.auth.session.collect { session ->
                when (session) {
                    is SessionState.SignedIn -> {
                        _state.update { it.copy(myId = session.userId) }
                        load()
                    }
                    SessionState.SignedOut -> _state.value = ActivityState()
                    SessionState.Loading -> Unit
                }
            }
        }
        // Each shown book's shelf in the user's library, kept current as either changes.
        viewModelScope.launch {
            combine(
                repository.observeBooks(null, ""),
                _state.map { s -> s.entries.map { it.book } + s.reading.map { it.book } }.distinctUntilChanged(),
            ) { library, books -> shelvesFor(books, library) }
                .flowOn(compute)
                .collect { shelves -> _state.update { it.copy(shelves = shelves) } }
        }
    }

    /** "Want to read" / "To buy" on a card: adds the book unless the library has it (one add at a time). */
    fun wantToRead(book: FeedBook) = add(book, ReadingStatus.TO_READ)

    fun wantToBuy(book: FeedBook) = add(book, ReadingStatus.WISHLIST)

    /** "✓ In your library": opens the user's copy of [book] with [open] (its library id). */
    fun openMine(book: FeedBook, open: (Long) -> Unit) {
        viewModelScope.launch { libraryBookFor(book, repository.observeBooks(null, "").first())?.let { open(it.id) } }
    }

    private fun add(book: FeedBook, status: ReadingStatus) {
        viewModelScope.launch {
            val added = adding.withLock { shelf.add(book, status) }
            LibraryShelf.message(added, status)?.let { m -> _state.update { it.copy(message = m) } }
        }
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    fun selectScope(scope: FeedScope) {
        if (scope == _state.value.scope) return
        _state.update { it.copy(scope = scope, entries = emptyList(), next = null, error = null, reading = emptyList()) }
        refresh()
    }

    /** Finds out whether the user has a profile (if not known yet), then loads the first page. */
    private suspend fun load() {
        _state.update { it.copy(loading = true, error = null) }
        try {
            if (!_state.value.signedIn) {
                val hasProfile = services.profiles.myProfile() != null
                _state.update { it.copy(signedIn = hasProfile) }
                if (!hasProfile) return
            }
            val scope = _state.value.scope
            // The feed, the friends list and the friends row load at the same time.
            val (page, hasFriends, reading) = coroutineScope {
                val page = async { services.activity.feed(scope) }
                val hasFriends = async {
                    if (scope == FeedScope.FRIENDS) services.friends.friends().isNotEmpty() else _state.value.hasFriends
                }
                val reading = async { if (scope == FeedScope.FRIENDS) friendsReading() else emptyList() }
                Triple(page.await(), hasFriends.await(), reading.await())
            }
            _state.update {
                if (it.scope != scope) it
                else it.copy(entries = page.entries, next = page.next, hasFriends = hasFriends, reading = reading)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = socialMessage(e)) }
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    /** The friends row; hidden (empty) when it can't be loaded, without disturbing the feed. */
    private suspend fun friendsReading(): List<FriendReading> = try {
        services.activity.friendsReading()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    fun loadMore() {
        val current = _state.value
        val cursor = current.next ?: return
        if (current.loadingMore || current.loading) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = services.activity.feed(current.scope, cursor)
                _state.update { s ->
                    if (s.scope != current.scope) s
                    else s.copy(entries = (s.entries + page.entries).distinctBy { it.id }, next = page.next)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = socialMessage(e)) }
            } finally {
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }

    /** Flips the heart at once; puts it back with a message if the server call fails. */
    fun toggleLike(entryId: Long) {
        val entry = _state.value.entries.firstOrNull { it.id == entryId } ?: return
        if (entry.actor.id == _state.value.myId) return  // the server refuses likes on your own entries
        likes.toggle(entry, ::replace) { e -> _state.update { it.copy(message = socialMessage(e)) } }
    }

    private fun replace(entryId: Long, change: (FeedEntry) -> FeedEntry) =
        _state.update { s -> s.copy(entries = s.entries.map { if (it.id == entryId) change(it) else it }) }

    fun showLikers(entryId: Long) = action { _state.update { it.copy(likers = services.activity.likers(entryId)) } }

    fun dismissLikers() = _state.update { it.copy(likers = null) }

    /** Details are trimmed, limited to 1000 characters, and sent as null when blank. */
    fun report(entry: FeedEntry, reason: ReportReason, details: String) = action {
        services.safety.report(entry.actor.id, reason, details.trim().take(1000).ifEmpty { null }, activityId = entry.id)
        _state.update { it.copy(message = "Thanks, we'll look into it") }
    }

    fun openBook(book: FeedBook) {
        _state.update { it.copy(openBook = book, openBookInLibrary = false) }
        viewModelScope.launch {
            // The same matching as the cards (another edition with the same title and author counts).
            val inLibrary = libraryBookFor(book, repository.observeBooks(null, "").first()) != null
            _state.update { if (it.openBook == book) it.copy(openBookInLibrary = inLibrary) else it }
        }
    }

    fun closeBook() = _state.update { it.copy(openBook = null, openBookInLibrary = false) }

    /** Adds the open book to the library as To read or To buy, unless it's already there. */
    fun addToLibrary(status: ReadingStatus) {
        val book = _state.value.openBook ?: return
        viewModelScope.launch {
            val added = adding.withLock { shelf.add(book, status) }
            _state.update {
                it.copy(
                    message = LibraryShelf.message(added, status) ?: it.message,
                    openBookInLibrary = it.openBookInLibrary || added != LibraryShelf.Added.FAILED,
                )
            }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = socialMessage(e)) }
            }
        }
    }
}
