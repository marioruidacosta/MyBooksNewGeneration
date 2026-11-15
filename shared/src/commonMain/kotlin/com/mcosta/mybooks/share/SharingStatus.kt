package com.mcosta.mybooks.share

import com.mcosta.mybooks.social.SocialServices
import com.mcosta.mybooks.social.model.Profile
import com.mcosta.mybooks.social.model.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Whether the signed-in user shares their reading activity.
 * [confirmed] is false while the profile couldn't be loaded (e.g. offline at app start): sharing is then
 * assumed on, which is harmless because the server ignores publishes when it's off.
 */
data class SharingState(
    val userId: String? = null,
    val hasProfile: Boolean = false,
    val shareActivity: Boolean = false,
    val confirmed: Boolean = true,
) {
    val activeOwner: String? get() = userId?.takeIf { hasProfile && shareActivity }
}

class SharingStatus(
    private val social: SocialServices,
    private val scope: CoroutineScope,
    /** Called when sharing becomes active (sign-in, app start, switched on): time to upload what's waiting. */
    private val onActivated: () -> Unit,
    /** Called after the server removed everything shared, with the user id. */
    private val onDisabled: suspend (owner: String) -> Unit,
    /** The session it follows: an account library's own account only (another account signed in counts as signed out). */
    private val session: Flow<SessionState> = social.auth.session,
) {
    private val _state = MutableStateFlow(SharingState())
    val state: StateFlow<SharingState> = _state.asStateFlow()
    private var retry: Job? = null

    init {
        scope.launch {
            session.collect { current ->
                retry?.cancel()
                if (current is SessionState.SignedIn) load(current.userId) else set(SharingState())
            }
        }
    }

    /** Reloads the profile (e.g. right after it was created). */
    suspend fun refresh() {
        _state.value.userId?.let { load(it) }
    }

    /** Turns sharing on or off on the server; off also forgets the local sharing state. */
    suspend fun setShareActivity(enabled: Boolean): Profile {
        val profile = social.profiles.setShareActivity(enabled)
        // Stop tracking first, so an edit made meanwhile isn't left waiting under a forgotten owner.
        set(SharingState(profile.id, hasProfile = true, shareActivity = profile.shareActivity))
        if (!enabled) onDisabled(profile.id)
        return profile
    }

    private suspend fun load(userId: String) {
        val profile = try {
            social.profiles.myProfile()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val known = _state.value.takeIf { it.userId == userId }
            set(known ?: SharingState(userId, hasProfile = true, shareActivity = true, confirmed = false))
            retry?.cancel()
            retry = scope.launch {
                delay(RETRY_DELAY)
                load(userId)
            }
            return
        }
        val loaded = SharingState(userId, hasProfile = profile != null, shareActivity = profile?.shareActivity == true)
        set(loaded)
        // Not sharing: drop anything tracked while sharing was only assumed on (e.g. offline at start),
        // so it can never be posted later.
        if (loaded.activeOwner == null) onDisabled(userId)
    }

    private fun set(new: SharingState) {
        val old = _state.value
        _state.value = new
        // Became active, or an assumed-active state (offline start) was just confirmed: upload what's waiting.
        val confirmedNow = !old.confirmed && new.confirmed
        if (new.activeOwner != null && (old.activeOwner == null || confirmedNow)) onActivated()
    }

    private companion object {
        val RETRY_DELAY = 60.seconds
    }
}
