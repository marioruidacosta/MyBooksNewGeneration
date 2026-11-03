package com.mcosta.mybooks.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the app looks: the book colours (default) or the wallpaper's (Android 12+); remembered between launches. */
class Appearance(private val prefs: Preferences?) {
    private val _followWallpaper = MutableStateFlow(prefs?.get(FOLLOW_WALLPAPER) == "true")
    val followWallpaper: StateFlow<Boolean> = _followWallpaper.asStateFlow()

    fun setFollowWallpaper(follow: Boolean) {
        _followWallpaper.value = follow
        prefs?.set(FOLLOW_WALLPAPER, follow.toString())
    }

    private companion object {
        const val FOLLOW_WALLPAPER = "appearance.followWallpaper"
    }
}
