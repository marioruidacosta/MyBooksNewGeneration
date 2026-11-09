@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.mcosta.mybooks

import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.FakeBookmarkDao
import com.mcosta.mybooks.data.FakeChallengeDao
import com.mcosta.mybooks.data.FakeEbookDao
import com.mcosta.mybooks.data.FakeQuoteDao
import com.mcosta.mybooks.data.FakeSyncQueueDao
import com.mcosta.mybooks.data.FakeSyncStateDao
import com.mcosta.mybooks.data.covers.PhotoShrinker
import com.mcosta.mybooks.data.db.BookEntity
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.social.FakeSocial
import com.mcosta.mybooks.sync.SyncingBookDao
import com.mcosta.mybooks.ui.social.AccountViewModel
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
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
class SyncWiringTest : MainDispatcherTest() {
    private val engineJob = SupervisorJob()

    @AfterTest
    fun stop() = engineJob.cancel()

    private val fake = FakeSocial()
    private val ana = fake.addUser("ana")
    private val queue = FakeSyncQueueDao()
    private val state = FakeSyncStateDao()
    private val books = FakeBookDao()
    private val db = SyncDatabase(books, FakeQuoteDao(), FakeEbookDao(), FakeBookmarkDao(), FakeChallengeDao(), queue, state)
    private var sync: LibrarySync? = null
    private var now = 0L
    private val recorder = SyncRecorder(queue, { now }, { syncRecording(sync) }, { it() })

    private fun TestScope.wire(social: com.mcosta.mybooks.social.SocialServices? = fake.services): LibrarySync? {
        sync = syncFor(social, db, recorder, CoroutineScope(StandardTestDispatcher(testScheduler) + engineJob), { now }, null, null, null)
        return sync
    }

    private suspend fun addBook(): Long = books.insert(
        BookEntity(
            isbn = null, title = "Os Maias", authors = "Eça de Queirós", publisher = null, publishedYear = null,
            pageCount = null, coverUrl = null, description = null, status = ReadingStatus.READ, rating = null,
            notes = "", dateAdded = 0, dateRead = null, searchText = "os maias",
        ),
    )

    private fun cloudRating(uuid: String) =
        fake.sync.stored()[SyncTable.BOOKS to uuid]?.fields?.get("rating")?.jsonPrimitive?.intOrNull

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

        // Signing out from Settings → Account.
        sync.signOut(keepLibrary = true)
        fake.auth.signOut()
        sync.signOutFinished()
        advanceUntilIdle()
        assertEquals(SyncStatus.Off, sync.status.value)
        assertFalse(recorder.enabled())
    }

    @Test
    fun expiredSessionKeepsRecordingAndShowsSignInAgain() = runTest {
        val sync = wire()!!
        val id = addBook()
        fake.signInAs(ana)
        advanceUntilIdle()
        assertIs<SyncStatus.Synced>(sync.status.value)
        assertEquals(1, fake.sync.calls.count { it.first == "merge" })

        // The session ends without the user asking (the refresh token was rejected).
        fake.auth.signOut()
        advanceUntilIdle()
        assertEquals(SyncStatus.SignInAgain, sync.status.value)
        assertTrue(recorder.enabled())

        // An edit meanwhile waits in the queue.
        now = 1_000
        SyncingBookDao(books, recorder).update(books.getById(id)!!.copy(rating = 4))
        val uuid = books.getById(id)!!.uuid
        assertEquals(listOf(uuid), queue.rows.value.map { it.rowId })

        // Signing in again picks up where it was: no second merge, and the edit goes up.
        fake.signInAs(ana)
        advanceUntilIdle()
        assertIs<SyncStatus.Synced>(sync.status.value)
        assertEquals(1, fake.sync.calls.count { it.first == "merge" })
        assertTrue(queue.rows.value.isEmpty())
        assertEquals(4, cloudRating(uuid))
    }

    @Test
    fun sessionExpiredWhileTheAppWasClosedShowsSignInAgain() = runTest {
        // Synced in an earlier run; this time the stored session can't be refreshed.
        state.rows["seq"] = "5"
        val sync = wire()!!
        fake.auth.signOut()
        advanceUntilIdle()
        assertEquals(SyncStatus.SignInAgain, sync.status.value)
        assertTrue(recorder.enabled())
    }

    @Test
    fun editsDuringStartupLoadingAreQueued() = runTest {
        // This device joined ana's synced library in an earlier run; the app has just started and the stored
        // session isn't restored yet.
        state.rows["merged:$ana"] = "1"
        fake.loading()
        val sync = wire()!!
        val edited = addBook()
        val removed = addBook()
        advanceUntilIdle()
        assertEquals(SyncStatus.Off, sync.status.value)
        assertTrue(recorder.enabled())

        now = 1_000
        val dao = SyncingBookDao(books, recorder)
        dao.update(books.getById(edited)!!.copy(rating = 4))
        // A delete too: kept as a marker, so it reaches the other devices.
        val removedUuid = books.getById(removed)!!.uuid
        dao.delete(removed)
        val editedUuid = books.getById(edited)!!.uuid
        assertEquals(setOf(editedUuid, removedUuid), queue.rows.value.map { it.rowId }.toSet())

        fake.signInAs(ana)
        advanceUntilIdle()
        assertIs<SyncStatus.Synced>(sync.status.value)
        assertTrue(queue.rows.value.isEmpty())
        assertEquals(4, cloudRating(editedUuid))
        assertEquals(
            true,
            fake.sync.stored()[SyncTable.BOOKS to removedUuid]?.fields?.get("deleted")?.jsonPrimitive?.booleanOrNull,
        )
    }

    @Test
    fun deleteAccountClearsQueueAndStopsRecording() = runTest {
        val sync = wire()!!
        fake.signInAs(ana)
        advanceUntilIdle()
        val vm = AccountViewModel(fake.services, object : PhotoShrinker {
            override fun shrink(photo: ByteArray, maxSide: Int) = photo
        }, sync = sync, workDispatcher = mainDispatcher)
        recorder.record(SyncTable.BOOKS, "waiting")

        vm.deleteAccount()
        advanceUntilIdle()

        assertTrue(vm.state.value.deleted)
        assertEquals(SyncStatus.Off, sync.status.value)
        assertFalse(recorder.enabled())
        assertTrue(queue.rows.value.isEmpty())
        assertTrue(state.rows.isEmpty())
        recorder.record(SyncTable.BOOKS, "after")
        assertTrue(queue.rows.value.isEmpty())
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
