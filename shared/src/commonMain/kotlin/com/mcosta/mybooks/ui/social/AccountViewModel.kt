package com.mcosta.mybooks.ui.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.data.covers.PhotoShrinker
import com.mcosta.mybooks.library.AddBooksOffer
import com.mcosta.mybooks.library.Libraries
import com.mcosta.mybooks.library.LibrarySessions
import com.mcosta.mybooks.library.OnSignOut
import com.mcosta.mybooks.share.SharingStatus
import com.mcosta.mybooks.social.SocialServices
import com.mcosta.mybooks.social.model.Person
import com.mcosta.mybooks.social.model.Profile
import com.mcosta.mybooks.social.model.Visibility
import com.mcosta.mybooks.social.socialMessage
import com.mcosta.mybooks.sync.LibrarySync
import com.mcosta.mybooks.sync.SignOutCheck
import com.mcosta.mybooks.sync.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AccountState(
    val profile: Profile? = null,
    val blocked: List<Person> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    /** True once the community account was deleted; the screen then closes. */
    val deleted: Boolean = false,
    /** True after signing out; the screen then closes. */
    val signedOut: Boolean = false,
    /** "Sign out" was tapped while the library syncs: a last sync runs before asking about the library. */
    val preparingSignOut: Boolean = false,
    /** "Sign out" was tapped while the library syncs: ask whether to keep a copy of it on this phone. */
    val askKeepLibrary: Boolean = false,
    /** With [askKeepLibrary]: what "Remove" would lose. */
    val signOutCheck: SignOutCheck = SignOutCheck(),
    /** Where library sync stands; null where sync isn't set up. */
    val sync: SyncInfo? = null,
    /** The first sign-in's "Add this phone's N books to your account?", while it waits for an answer. */
    val offer: AddBooksOffer? = null,
    /** Live books in the phone's own (no-account) library: "Add this phone's books" shows while there are any. */
    val phoneBooks: Int = 0,
    /** "Add this phone's books" was tapped: the same question, asked from here. */
    val askAddPhoneBooks: Boolean = false,
    /** The phone's books are moving into the account's library. */
    val addingPhoneBooks: Boolean = false,
) {
    /** The "Add this phone's N books" question to show (N), or null. */
    val addPhoneBooksQuestion: Int?
        get() = when {
            addingPhoneBooks -> null
            offer != null -> offer.count
            askAddPhoneBooks && phoneBooks > 0 -> phoneBooks
            else -> null
        }

    /** Settings → Account's "Add this phone's books": the phone has books of its own, and no question is open. */
    val canAddPhoneBooks: Boolean get() = phoneBooks > 0 && offer == null && !addingPhoneBooks
}

/** The "Library sync" line in Settings → Account. */
data class SyncInfo(
    val status: SyncStatus,
    /** (books done, books in all) while the first sign-in combines the libraries; null otherwise. */
    val merging: Pair<Int, Int>? = null,
    /** The cloud has no room for more cover photos. */
    val coversFull: Boolean = false,
    /** Changes the server refused since sync started (this phone keeps its values). */
    val setAside: Int = 0,
)

/** The keep-library question's lines after the question itself: what "Remove" would lose. */
fun SignOutCheck.warnings(): List<String> = buildList {
    when {
        waiting == 1 -> add("1 change hasn't reached your account yet. Removing now loses it.")
        waiting > 1 -> add("$waiting changes haven't reached your account yet. Removing now loses them.")
        unconfirmed -> add("Some changes may not have reached your account yet. Removing now loses them.")
    }
    if (ebookFiles) add("Ebook files are only on this phone and will be deleted.")
}

/** The keep-library question's "Remove" button. */
val SignOutCheck.removeLabel: String get() = if (anythingWaiting) "Remove anyway" else "Remove"

/** The signed-in user's community account: profile, avatar, visibility, blocked people, sign out, delete. */
class AccountViewModel(
    private val services: SocialServices,
    private val shrinker: PhotoShrinker,
    /** Activity sharing; null in tests that don't involve it (the switch then talks to the server only). */
    private val sharing: SharingStatus? = null,
    /** Cloud sync of the library; null where it isn't set up (sign out then never asks about the library). */
    private val sync: LibrarySync? = null,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Switches libraries on sign-out; null in tests without libraries (signing out then only ends the session). */
    private val sessions: LibrarySessions<*>? = null,
    /** The phone's libraries, for moving the phone's books into the account's; null in tests without libraries. */
    private val libraries: Libraries<*>? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = _state.asStateFlow()

    init {
        if (sync != null) {
            viewModelScope.launch {
                combine(sync.status, sync.mergeProgress, sync.coversFull, sync.setAside, ::SyncInfo)
                    .collect { info -> _state.update { it.copy(sync = info) } }
            }
        }
        if (libraries != null) {
            viewModelScope.launch { libraries.offer.collect { offer -> _state.update { it.copy(offer = offer) } } }
            countPhoneBooks()
        }
        refresh()
    }

    private fun countPhoneBooks() {
        val libraries = libraries ?: return
        viewModelScope.launch {
            val count = try {
                libraries.phoneBooks()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                0
            }
            _state.update { it.copy(phoneBooks = count) }
        }
    }

    /** "Add this phone's books" in Settings → Account: asks first, as at the first sign-in. */
    fun askAddPhoneBooks() = _state.update { it.copy(askAddPhoneBooks = true) }

    /** "Add them": the phone's books move into the account's library, and sync to its other devices. */
    fun addPhoneBooks() {
        val libraries = libraries ?: return
        if (_state.value.addingPhoneBooks) return
        _state.update { it.copy(askAddPhoneBooks = false, addingPhoneBooks = true, message = null) }
        viewModelScope.launch {
            try {
                libraries.addPhoneBooks()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nothing is lost: what didn't move is still on the phone, and it can be tried again.
                _state.update { it.copy(message = "Couldn't add this phone's books. Try again.") }
            } finally {
                _state.update { it.copy(addingPhoneBooks = false) }
            }
            countPhoneBooks()
        }
    }

    /** "Not now": the phone's books stay where they are (they can still be added from here later). */
    fun notNow() {
        _state.update { it.copy(askAddPhoneBooks = false) }
        if (_state.value.offer != null) libraries?.notNow()
    }

    fun refresh() = launchAction {
        val profile = services.profiles.myProfile()
        val blocked = services.safety.blockedPeople()
        _state.update { it.copy(profile = profile, blocked = blocked) }
    }

    fun saveDisplayName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(message = "Enter a name") }
            return
        }
        val visibility = _state.value.profile?.visibility ?: Visibility.FRIENDS
        launchAction { _state.update { it.copy(profile = services.profiles.updateProfile(trimmed.take(50), visibility)) } }
    }

    fun setVisibility(visibility: Visibility) {
        val name = _state.value.profile?.displayName ?: return
        launchAction { _state.update { it.copy(profile = services.profiles.updateProfile(name, visibility)) } }
    }

    /** Shrinks the photo to 512 px off the main thread, then uploads it. */
    fun setAvatar(photo: ByteArray) = launchAction {
        val small = withContext(workDispatcher) { shrinker.shrink(photo, maxSide = 512) }
        _state.update { it.copy(profile = services.profiles.uploadAvatar(small)) }
    }

    /** "Share my reading activity"; off also removes everything shared, on the server and on this phone. */
    fun setShareActivity(enabled: Boolean) = launchAction {
        val profile = sharing?.setShareActivity(enabled) ?: services.profiles.setShareActivity(enabled)
        _state.update { it.copy(profile = profile) }
    }

    fun unblock(userId: String) = launchAction {
        services.safety.unblock(userId)
        _state.update { s -> s.copy(blocked = s.blocked.filterNot { it.id == userId }) }
    }

    private var preparing: Job? = null

    /**
     * "Sign out" tapped: while the library syncs, a last sync first (20 s at most), then ask whether to keep the
     * library, saying what "Remove" would lose ([signOut] with the answer).
     */
    fun signOut() {
        val syncing = sync != null && sync.status.value != SyncStatus.Off
        if (syncing) {
            if (preparing?.isActive == true) return
            preparing = viewModelScope.launch {
                _state.update { it.copy(preparingSignOut = true, message = null) }
                try {
                    val check = try {
                        sync.prepareSignOut()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Nothing could be checked: warn rather than reassure.
                        SignOutCheck(unconfirmed = true)
                    }
                    _state.update { it.copy(askKeepLibrary = true, signOutCheck = check) }
                } finally {
                    _state.update { it.copy(preparingSignOut = false) }
                }
            }
            return
        }
        launchAction {
            // Nothing to lose by keeping it: the account's library stays on the phone, and the phone shows its own.
            leave(OnSignOut.KEEP) { services.auth.signOut() }
            _state.update { AccountState(signedOut = true) }
        }
    }

    /**
     * The answer to "Keep a copy of your library on this device?": the phone then shows its own library, and "Remove"
     * deletes the account's.
     */
    fun signOut(keepLibrary: Boolean) = launchAction {
        preparing?.cancel()
        _state.update { it.copy(askKeepLibrary = false) }
        leave(if (keepLibrary) OnSignOut.KEEP else OnSignOut.REMOVE) { services.auth.signOut() }
        _state.update { AccountState(signedOut = true) }
    }

    /** "Retry" after "Couldn't sync": tries again now instead of waiting. */
    fun retrySync() {
        val sync = sync ?: return
        viewModelScope.launch { sync.refresh() }
    }

    /** The keep-library question was dismissed: still signed in. */
    fun cancelSignOut() {
        preparing?.cancel()
        _state.update { it.copy(askKeepLibrary = false, preparingSignOut = false) }
    }

    /**
     * Deletes the community account. The phone then shows its own library, with the account's books moved into it (the
     * account's library is removed; it stays if they can't be moved).
     */
    fun deleteAccount() = launchAction {
        leave(OnSignOut.MOVE_TO_PHONE) { services.profiles.deleteAccount() }
        _state.update { AccountState(deleted = true) }
    }

    /** Ends the session with [signOutOfServer], through [sessions] (which switches libraries) where there is one. */
    private suspend fun leave(after: OnSignOut, signOutOfServer: suspend () -> Unit) {
        if (sessions != null) return sessions.signOut(after, signOutOfServer)
        sync?.signOut()
        try {
            signOutOfServer()
        } finally {
            sync?.signOutFinished()
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun launchAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = socialMessage(e)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
