package com.mcosta.mybooks.library

import com.mcosta.mybooks.data.FakeSyncStateDao
import com.mcosta.mybooks.data.PinnedTags
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DbPreferencesTest {
    @Test
    fun setThenGetAfterReload() = runTest {
        val dao = FakeSyncStateDao()
        val prefs = DbPreferences(dao, this)
        prefs.load()
        prefs.set("a", "1")
        assertEquals("1", prefs.get("a"))
        advanceUntilIdle()
        val again = DbPreferences(dao, this)
        assertNull(again.get("a"))
        again.load()
        assertEquals("1", again.get("a"))
    }

    @Test
    fun keysAreStoredUnderPrefPrefix() = runTest {
        val dao = FakeSyncStateDao()
        dao.rows["merged:x"] = "1"
        val prefs = DbPreferences(dao, this)
        prefs.set("home.pinnedTags", "x")
        advanceUntilIdle()
        assertEquals("x", dao.rows["pref:home.pinnedTags"])
        val again = DbPreferences(dao, this)
        again.load()
        assertNull(again.get("merged:x"))
    }

    @Test
    fun rapidSetsOfTheSameKeyKeepTheLast() = runTest {
        val dao = FakeSyncStateDao()
        val prefs = DbPreferences(dao, this)
        repeat(50) { prefs.set("k", "v$it") }
        advanceUntilIdle()
        assertEquals("v49", dao.rows["pref:k"])
    }

    @Test
    fun pinnedTagsKeepsItsApiOnDbPreferences() = runTest {
        val dao = FakeSyncStateDao()
        val prefs = DbPreferences(dao, this)
        val pinned = PinnedTags(prefs)
        pinned.setPinned("classics", true)
        advanceUntilIdle()
        val again = DbPreferences(dao, this)
        again.load()
        assertEquals(listOf("classics"), PinnedTags(again).tags.value)
    }
}
