package com.mcosta.mybooks.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.mcosta.mybooks.AppContainer
import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.platform.PlatformServices
import com.mcosta.mybooks.ui.theme.MyBooksTheme

val LocalCoverStore = staticCompositionLocalOf<CoverStore> { error("No CoverStore provided") }
val LocalPlatformServices = staticCompositionLocalOf<PlatformServices> { error("No PlatformServices provided") }

/** The whole app, shared by Android and iOS. */
@Composable
fun App(container: AppContainer, services: PlatformServices) {
    val followWallpaper by container.appearance.followWallpaper.collectAsState()
    MyBooksTheme(followWallpaper) {
        CompositionLocalProvider(
            LocalCoverStore provides container.coverStore,
            LocalPlatformServices provides services,
        ) {
            MyBooksNavHost(container)
        }
    }
}
