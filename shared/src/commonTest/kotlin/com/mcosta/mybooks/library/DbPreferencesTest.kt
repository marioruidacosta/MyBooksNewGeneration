package com.mcosta.mybooks.library

import com.mcosta.mybooks.data.FakeSyncStateDao
import com.mcosta.mybooks.data.PinnedTags
import com.mcosta.mybooks.data.db.SyncStateDao
import com.mcosta.mybooks.data.db.SyncStateEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

    @Test
    fun setBeforeLoadWinsOverStored() = runTest {
        val dao = FakeSyncStateDao()
        dao.rows["pref:k"] = "old"
        dao.rows["pref:other"] = "o"
        val prefs = DbPreferences(dao, this)
        prefs.set("k", "new")
        prefs.load()
        assertEquals("new", prefs.get("k"))
        assertEquals("o", prefs.get("other"))
    }

    @Test
    fun getBeforeLoadIsNull() = runTest {
        val dao = FakeSyncStateDao()
        dao.rows["pref:k"] = "v"
        assertNull(DbPreferences(dao, this).get("k"))
    }

    @Test
    fun loadIgnoresPrefsTimeRows() = runTest {
        val dao = FakeSyncStateDao()
        dao.rows["prefs/home.pinnedTags"] = "123"
        val prefs = DbPreferences(dao, this)
        prefs.load()
        assertNull(prefs.get("home.pinnedTags"))
        assertNull(prefs.get("prefs/home.pinnedTags"))
    }

    @Test
    fun flushWritesPendingAfterScopeCancelled() = runTest {
        val dao = FakeSyncStateDao()
        val job = Job()
        val prefs = DbPreferences(dao, CoroutineScope(job))
        prefs.set("k", "v")
        job.cancel()
        prefs.flush()
        assertEquals("v", dao.rows["pref:k"])
    }

    @Test
    fun failedWriteStaysPendingAndDoesNotCrash() = runTest {
        val dao = FakeSyncStateDao()
        var failing = true
        val flaky = object : SyncStateDao by dao {
            override suspend fun put(entry: SyncStateEntity) {
                if (failing) throw IllegalStateException("disk full")
                dao.put(entry)
            }
            override suspend fun set(key: String, value: String) = put(SyncStateEntity(key, value))
        }
        val prefs = DbPreferences(flaky, this)
        prefs.set("k", "v")
        advanceUntilIdle()           // must not throw out of the scope
        assertNull(dao.rows["pref:k"])
        failing = false
        prefs.flush()
        assertEquals("v", dao.rows["pref:k"])
    }
}
