package com.mcosta.mybooks.data

import kotlin.test.Test
import kotlin.test.assertEquals

class AppearanceTest {
    private class MemoryPreferences : Preferences {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String) { values[key] = value }
    }

    @Test
    fun bookColoursByDefaultAndTheChoiceIsRemembered() {
        val prefs = MemoryPreferences()
        val appearance = Appearance(prefs)
        assertEquals(false, appearance.followWallpaper.value)
        appearance.setFollowWallpaper(true)
        assertEquals(true, appearance.followWallpaper.value)
        assertEquals(true, Appearance(prefs).followWallpaper.value)
        Appearance(prefs).setFollowWallpaper(false)
        assertEquals(false, Appearance(prefs).followWallpaper.value)
    }

    @Test
    fun withoutStorageItStillWorksForTheSession() {
        val appearance = Appearance(null)
        appearance.setFollowWallpaper(true)
        assertEquals(true, appearance.followWallpaper.value)
    }
}
