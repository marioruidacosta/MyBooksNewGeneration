package com.mcosta.mybooks.ui.social

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mcosta.mybooks.share.SharingStatus
import com.mcosta.mybooks.social.SocialServices
import com.mcosta.mybooks.sync.LibrarySync
import com.mcosta.mybooks.sync.SyncStatus
import com.mcosta.mybooks.ui.LocalPlatformServices

/** The community account: the sign-in flow until there's a profile, then the account screen. */
@Composable
fun AccountRoute(services: SocialServices, sharing: SharingStatus?, onBack: () -> Unit, sync: LibrarySync? = null) {
    val signIn = viewModel { SignInViewModel(services.auth, services.profiles, onProfileCreated = { sharing?.refresh() }) }
    val state by signIn.state.collectAsStateWithLifecycle()
    val syncStatus = sync?.status?.collectAsStateWithLifecycle()?.value
    if (state.step == SignInStep.DONE) {
        val shrinker = LocalPlatformServices.current.shrinker
        AccountScreen(viewModel { AccountViewModel(services, shrinker, sharing, sync = sync) }, onBack = onBack)
    } else {
        // The session expired: the library keeps its changes until the user signs in again.
        SignInScreen(signIn, onBack = onBack, onDone = {}, notice = if (syncStatus == SyncStatus.SignInAgain) "Sign in again to sync" else null)
    }
}
