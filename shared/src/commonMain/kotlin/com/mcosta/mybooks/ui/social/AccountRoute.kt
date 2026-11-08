package com.mcosta.mybooks.ui.social

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mcosta.mybooks.share.SharingStatus
import com.mcosta.mybooks.social.SocialServices
import com.mcosta.mybooks.sync.LibrarySync
import com.mcosta.mybooks.ui.LocalPlatformServices

/** The community account: the sign-in flow until there's a profile, then the account screen. */
@Composable
fun AccountRoute(services: SocialServices, sharing: SharingStatus?, onBack: () -> Unit, sync: LibrarySync? = null) {
    val signIn = viewModel { SignInViewModel(services.auth, services.profiles, onProfileCreated = { sharing?.refresh() }) }
    val state by signIn.state.collectAsStateWithLifecycle()
    if (state.step == SignInStep.DONE) {
        val shrinker = LocalPlatformServices.current.shrinker
        AccountScreen(viewModel { AccountViewModel(services, shrinker, sharing, sync = sync) }, onBack = onBack)
    } else {
        SignInScreen(signIn, onBack = onBack, onDone = {})
    }
}
