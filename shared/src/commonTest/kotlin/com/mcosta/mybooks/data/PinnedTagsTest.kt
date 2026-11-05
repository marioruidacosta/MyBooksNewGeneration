package com.mcosta.mybooks.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PinnedTagsTest {
    private val stored = mutableMapOf<String, String>()
    private val prefs = object : Preferences {
        override fun get(key: String) = stored[key]
        override fun set(key: String, value: String) { stored[key] = value }
    }

    @Test
    fun pinnedTagsAreKeptInTheOrderPinnedAndRemembered() {
        val pinned = PinnedTags(prefs)
        pinned.setPinned("classics", true)
        pinned.setPinned("Gift from Ana", true)
        pinned.setPinned("CLASSICS", true)          // the same tag: moves to the end, once
        assertEquals(listOf("Gift from Ana", "CLASSICS"), pinned.tags.value)
        assertTrue(pinned.isPinned("gift from ana"))
        assertEquals(listOf("Gift from Ana", "CLASSICS"), PinnedTags(prefs).tags.value)
        pinned.setPinned("classics", false)
        assertFalse(pinned.isPinned("Classics"))
        assertEquals(listOf("Gift from Ana"), PinnedTags(prefs).tags.value)
    }
}
