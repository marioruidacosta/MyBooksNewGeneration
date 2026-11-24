package com.mcosta.mybooks.ui.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.social.AuthService
import com.mcosta.mybooks.social.ProfileService
import com.mcosta.mybooks.social.SocialException
import com.mcosta.mybooks.social.model.SessionState
import com.mcosta.mybooks.social.normalizeUsername
import com.mcosta.mybooks.social.socialMessage
import com.mcosta.mybooks.social.usernameProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** LOADING: the session or profile isn't known yet (no form is shown; an error offers a retry). */
enum class SignInStep { LOADING, EMAIL, CODE, USERNAME, DONE }

data class SignInState(
    val step: SignInStep = SignInStep.LOADING,
    val email: String = "",
    val code: String = "",
    val username: String = "",
    val usernameError: String? = null,
    /** null while unknown (not checked yet, or the username is invalid). */
    val usernameAvailable: Boolean? = null,
    val displayName: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null,
)

private val emailPattern = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

/** Email → 6-digit code → (first time) username and display name. */
class SignInViewModel(
    private val auth: AuthService,
    private val profiles: ProfileService,
    /** Called once a new profile exists (activity sharing then starts). */
    private val onProfileCreated: suspend () -> Unit = {},
    /** Runs just before a code is verified: the new session starts its own clock of use. */
    private val beforeVerify: () -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(SignInState())
    val state: StateFlow<SignInState> = _state.asStateFlow()
    private var availabilityJob: Job? = null

    init {
        viewModelScope.launch {
            auth.session.collect { session ->
                when (session) {
                    SessionState.Loading -> _state.update { it.copy(step = SignInStep.LOADING) }
                    SessionState.SignedOut -> _state.update { it.copy(step = SignInStep.EMAIL, code = "") }
                    is SessionState.SignedIn -> resolveSignedIn()
                }
            }
        }
    }

    /** Signed in: the username step if there's no profile yet, else done. Stays LOADING (with an error) on failure. */
    private suspend fun resolveSignedIn() {
        attempt { _state.update { it.copy(step = stepAfterSignIn()) } }
    }

    /** Retries finding out whether the signed-in user has a profile (after an offline failure). */
    fun retry() {
        viewModelScope.launch { resolveSignedIn() }
    }

    private suspend fun stepAfterSignIn() = if (profiles.myProfile() == null) SignInStep.USERNAME else SignInStep.DONE

    /** Runs [action] with busy/error handling; returns the failure, or null if it worked. */
    private suspend fun attempt(action: suspend () -> Unit): Exception? {
        _state.update { it.copy(busy = true, error = null, info = null) }
        return try {
            action()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = socialMessage(e)) }
            e
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun setEmail(email: String) = _state.update { it.copy(email = email, error = null) }

    fun sendCode() {
        val email = _state.value.email.trim()
        if (!emailPattern.matches(email)) {
            _state.update { it.copy(error = "Enter a valid email") }
            return
        }
        viewModelScope.launch {
            if (attempt { auth.sendCode(email) } == null) _state.update { it.copy(email = email, step = SignInStep.CODE, code = "") }
        }
    }

    fun resend() {
        viewModelScope.launch {
            if (attempt { auth.sendCode(_state.value.email) } == null) _state.update { it.copy(info = "We sent a new code") }
        }
    }

    fun setCode(code: String) = _state.update { it.copy(code = code.filter(Char::isDigit).take(6), error = null) }

    fun verify() {
        val code = _state.value.code
        if (code.length != 6) {
            _state.update { it.copy(error = "Enter the 6-digit code") }
            return
        }
        viewModelScope.launch {
            attempt {
                beforeVerify()
                auth.verifyCode(_state.value.email, code)
                val next = stepAfterSignIn()
                _state.update { it.copy(step = next) }
            }
        }
    }

    /** Normalizes as typed; checks availability 300 ms after typing stops, only for valid names. */
    fun setUsername(raw: String) {
        val username = normalizeUsername(raw)
        val problem = usernameProblem(username)
        _state.update { it.copy(username = username, usernameError = problem, usernameAvailable = null, error = null) }
        availabilityJob?.cancel()
        if (problem != null) return
        availabilityJob = viewModelScope.launch {
            delay(300)
            val available = try {
                profiles.isUsernameAvailable(username)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _state.update { if (it.username == username) it.copy(usernameAvailable = available) else it }
        }
    }

    fun setDisplayName(name: String) = _state.update { it.copy(displayName = name.take(50), error = null) }

    fun createProfile() {
        val s = _state.value
        val problem = usernameProblem(s.username)
        when {
            problem != null -> _state.update { it.copy(usernameError = problem) }
            s.displayName.isBlank() -> _state.update { it.copy(error = "Enter a name") }
            else -> viewModelScope.launch {
                val failure = attempt { profiles.createProfile(s.username, s.displayName.trim()) }
                if (failure == null) onProfileCreated()
                _state.update {
                    when {
                        failure == null -> it.copy(step = SignInStep.DONE)
                        (failure as? SocialException)?.kind == SocialException.Kind.USERNAME_TAKEN -> it.copy(usernameAvailable = false)
                        else -> it
                    }
                }
            }
        }
    }
}
