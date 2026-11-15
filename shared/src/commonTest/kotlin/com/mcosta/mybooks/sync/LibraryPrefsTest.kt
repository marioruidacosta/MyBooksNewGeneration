package com.mcosta.mybooks.sync

import com.mcosta.mybooks.data.PinnedTags
import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.data.suggestions.HiddenSuggestions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryPrefsTest {
    private val stored = mutableMapOf<String, String>()
    private val prefs = object : Preferences {
        override fun get(key: String) = stored[key]
        override fun set(key: String, value: String) { stored[key] = value }
    }
    private val changed = mutableListOf<String>()
    // Told at once, so a change reported late can't hide among the pulled ones.
    private val now = CoroutineScope(Dispatchers.Unconfined)
    private val pinned = PinnedTags(prefs, onChanged = { changed += it }, scope = now)
    private val hidden = HiddenSuggestions(prefs, onChanged = { changed += it }, scope = now)
    private val synced = LibraryPrefs(pinned, hidden)

    @Test
    fun bothPreferencesSync() {
        assertEquals(setOf("home.pinnedTags", "suggestions.hidden"), synced.keys.toSet())
    }

    @Test
    fun valuesAreReadForUpload() {
        pinned.setPinned("Clássicos", true)
        pinned.setPinned("Poesia", true)
        hidden.addAll(listOf("b", "a"))
        assertEquals("Clássicos\nPoesia", synced.get("home.pinnedTags"))
        assertEquals("a\nb", synced.get("suggestions.hidden"))
        assertNull(synced.get("something.else"))
    }

    @Test
    fun pulledValuesShowAtOnceAndAreKeptButNotRecorded() {
        pinned.setPinned("Velho", true)
        hidden.hide("old")
        assertEquals(listOf("home.pinnedTags", "suggestions.hidden"), changed)
        changed.clear()

        synced.set("home.pinnedTags", "Clássicos\nPoesia")
        synced.set("suggestions.hidden", "x\ny")

        assertEquals(listOf("Clássicos", "Poesia"), pinned.tags.value)
        assertEquals(setOf("x", "y"), hidden.keys.value)
        // Kept on the phone: a new start reads them.
        assertEquals(listOf("Clássicos", "Poesia"), PinnedTags(prefs).tags.value)
        assertEquals(setOf("x", "y"), HiddenSuggestions(prefs).keys.value)
        // Not sent back.
        assertTrue(changed.isEmpty())
    }

    @Test
    fun pulledRemovalClearsTheValue() {
        pinned.setPinned("Velho", true)
        synced.set("home.pinnedTags", null)
        assertTrue(pinned.tags.value.isEmpty())
        assertTrue(PinnedTags(prefs).tags.value.isEmpty())
    }
}
