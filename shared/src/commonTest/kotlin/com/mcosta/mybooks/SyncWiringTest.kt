@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.mcosta.mybooks

import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.FakeBookmarkDao
import com.mcosta.mybooks.data.FakeChallengeDao
import com.mcosta.mybooks.data.FakeEbookDao
import com.mcosta.mybooks.data.FakeQuoteDao
import com.mcosta.mybooks.data.FakeSyncQueueDao
import com.mcosta.mybooks.data.FakeSyncStateDao
import com.mcosta.mybooks.social.FakeSocial
import com.mcosta.mybooks.sync.LibrarySync
import com.mcosta.mybooks.sync.SyncDatabase
import com.mcosta.mybooks.sync.SyncRecorder
import com.mcosta.mybooks.sync.SyncStatus
import com.mcosta.mybooks.sync.SyncTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How AppContainer ties library sync to the session, and local changes to sync. */
class SyncWiringTest {
    private val engineJob = SupervisorJob()

    @AfterTest
    fun stop() = engineJob.cancel()

    private val fake = FakeSocial()
    private val ana = fake.addUser("ana")
    private val queue = FakeSyncQueueDao()
    private val state = FakeSyncStateDao()
    private val db = SyncDatabase(FakeBookDao(), FakeQuoteDao(), FakeEbookDao(), FakeBookmarkDao(), FakeChallengeDao(), queue, state)
    private var sync: LibrarySync? = null
    private val recorder = SyncRecorder(queue, { 0L }, { syncRecording(sync) }, { it() })

    private fun TestScope.wire(social: com.mcosta.mybooks.social.SocialServices? = fake.services): LibrarySync? {
        sync = syncFor(social, db, recorder, CoroutineScope(StandardTestDispatcher(testScheduler) + engineJob), { 0L }, null, null, null)
        return sync
    }

    @Test
    fun noSyncWithoutTheCommunity() = runTest {
        assertNull(wire(social = null))
        assertFalse(recorder.enabled())
    }

    @Test
    fun syncFollowsTheSession() = runTest {
        val sync = wire()!!
        advanceUntilIdle()
        assertEquals(SyncStatus.Off, sync.status.value)
        assertFalse(recorder.enabled())

        fake.signInAs(ana)
        advanceUntilIdle()
        assertIs<SyncStatus.Synced>(sync.status.value)
        assertTrue(recorder.enabled())

        fake.auth.signOut()
        advanceUntilIdle()
        assertEquals(SyncStatus.Off, sync.status.value)
        assertFalse(recorder.enabled())
    }

    @Test
    fun changesAreQueuedOnlyWhileSyncing() = runTest {
        val sync = wire()!!
        recorder.record(SyncTable.BOOKS, "before")
        assertTrue(queue.rows.value.isEmpty())

        fake.signInAs(ana)
        advanceUntilIdle()
        // Looked at before the engine runs again (it would send it).
        recorder.record(SyncTable.BOOKS, "during")
        assertEquals(listOf("during"), queue.rows.value.map { it.rowId })

        // "Keep" at sign-out: the library stays, and later edits aren't queued.
        sync.signOut(keepLibrary = true)
        fake.auth.signOut()
        sync.signOutFinished()
        advanceUntilIdle()
        recorder.record(SyncTable.BOOKS, "after")
        assertTrue(queue.rows.value.isEmpty())
        assertFalse(recorder.enabled())
    }
}
