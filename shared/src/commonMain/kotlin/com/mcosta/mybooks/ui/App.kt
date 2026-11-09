package com.mcosta.mybooks.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import com.mcosta.mybooks.AppContainer
import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.platform.PlatformServices
import com.mcosta.mybooks.ui.theme.MyBooksTheme

val LocalCoverStore = staticCompositionLocalOf<CoverStore> { error("No CoverStore provided") }
val LocalPlatformServices = staticCompositionLocalOf<PlatformServices> { error("No PlatformServices provided") }

/** The whole app, shared by Android and iOS. */
@Composable
fun App(
    container: AppContainer,
    services: PlatformServices,
    /** A screen to open now (from a notification); [onOpened] is called once it is. */
    openRoute: String? = null,
    onOpened: () -> Unit = {},
) {
    val followWallpaper by container.appearance.followWallpaper.collectAsState()
    // Back in the foreground: changes made on other devices meanwhile come in (and this phone's go out).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (container.sync != null) container.applicationScope.launch { container.syncNow() }
    }
    MyBooksTheme(followWallpaper) {
        CompositionLocalProvider(
            LocalCoverStore provides container.coverStore,
            LocalPlatformServices provides services,
        ) {
            MyBooksNavHost(container, openRoute, onOpened)
        }
    }
}
