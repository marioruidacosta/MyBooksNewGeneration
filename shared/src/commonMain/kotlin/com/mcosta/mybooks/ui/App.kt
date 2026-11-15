package com.mcosta.mybooks.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.launch
import com.mcosta.mybooks.AppContainer
import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.library.Libraries
import com.mcosta.mybooks.library.LibraryHold
import com.mcosta.mybooks.library.LibrarySpace
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
    val space by container.libraries.active.collectAsState()
    // Back in the foreground: changes made on other devices meanwhile come in (and this phone's go out).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (container.sync != null) container.applicationScope.launch { container.syncNow() }
    }
    MyBooksTheme(followWallpaper) {
        // A switch rebuilds every screen and view model against the new library; nothing of the old one stays.
        key(space.id.key) {
            val shown = remember(space) { ShownLibrary(container.libraries, space) }
            // A switch got there first: the next composition shows the new library.
            if (shown.hold != null) {
                val spaceOwner = remember(space) { LibraryViewModels() }
                DisposableEffect(spaceOwner) { onDispose { spaceOwner.viewModelStore.clear() } }
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides spaceOwner,
                    LocalCoverStore provides space.coverStore,
                    LocalPlatformServices provides services,
                ) {
                    MyBooksNavHost(container, openRoute, onOpened)
                }
            }
        }
    }
}

/** The view models of the screens showing one library: cleared when the screens move to another library. */
private class LibraryViewModels : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/**
 * Holds [space] open while the composition shows it: taken as the composition builds (before any screen reads the
 * library), released when it leaves (after the view models above it are cleared) or is abandoned.
 */
private class ShownLibrary(libraries: Libraries<LibrarySpace>, space: LibrarySpace) : RememberObserver {
    val hold: LibraryHold? = libraries.hold(space)

    override fun onRemembered() = Unit

    override fun onForgotten() {
        hold?.release()
    }

    override fun onAbandoned() {
        hold?.release()
    }
}
