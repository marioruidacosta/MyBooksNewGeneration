@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.mcosta.mybooks

import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.FakeBookmarkDao
import com.mcosta.mybooks.data.FakeChallengeDao
import com.mcosta.mybooks.data.FakeEbookDao
import com.mcosta.mybooks.data.FakeQuoteDao
import com.mcosta.mybooks.data.FakeShareStateDao
import com.mcosta.mybooks.data.FakeSyncQueueDao
import com.mcosta.mybooks.data.FakeSyncStateDao
import com.mcosta.mybooks.data.covers.PhotoShrinker
import com.mcosta.mybooks.library.FakeLibraries
import com.mcosta.mybooks.library.LibraryId
import com.mcosta.mybooks.social.FakeSocial
import com.mcosta.mybooks.social.model.SessionState
import com.mcosta.mybooks.sync.SyncDatabase
import com.mcosta.mybooks.sync.SyncRecorder
import com.mcosta.mybooks.sync.SyncStatus
import com.mcosta.mybooks.ui.social.AccountViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Which libraries sync and share, and how the account screen's sign-outs reach the libraries. */
class SyncWiringTest : MainDispatcherTest() {
    private val engineJob = SupervisorJob()

    @AfterTest
    fun stop() = engineJob.cancel()

    private val fake = FakeSocial()
    private val ana = fake.addUser("ana")
    private val queue = FakeSyncQueueDao()
    private val db = SyncDatabase(FakeBookDao(), FakeQuoteDao(), FakeEbookDao(), FakeBookmarkDao(), FakeChallengeDao(), queue, FakeSyncStateDao())
    private val recorder = SyncRecorder(queue, { 0L }, { false }, { it() })

    @Test
    fun onlyAccountLibrariesSyncAndShare() = runTest(timeout = 30.seconds) {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + engineJob)
        val account = LibraryId.Account(ana)

        assertNotNull(syncFor(account, fake.services, db, recorder, scope, { 0L }, null, null, null))
        assertNotNull(sharingFor(account, fake.services, FakeShareStateDao(), scope) { null })
        assertNull(syncFor(LibraryId.Local, fake.services, db, recorder, scope, { 0L }, null, null, null))
        assertNull(sharingFor(LibraryId.Local, fake.services, FakeShareStateDao(), scope) { null })
        // Without the community, nothing syncs or shares, and nothing is queued.
        assertNull(syncFor(account, null, db, recorder, scope, { 0L }, null, null, null))
        assertNull(sharingFor(account, null, FakeShareStateDao(), scope) { null })
        assertFalse(syncRecording(null))
    }

    @Test
    fun anAccountLibrarySharesOnlyForItsOwnAccount() = runTest(timeout = 30.seconds) {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + engineJob)
        val carla = fake.addUser("carla")
        val sharing = sharingFor(LibraryId.Account(ana), fake.services, FakeShareStateDao(), scope) { null }!!

        fake.signInAs(carla)
        advanceUntilIdle()
        assertNull(sharing.status.state.value.userId)

        fake.signInAs(ana)
        advanceUntilIdle()
        assertEquals(ana, sharing.status.state.value.userId)
    }

    private val shrinker = object : PhotoShrinker {
        override fun shrink(photo: ByteArray, maxSide: Int) = photo
    }

    @Test
    fun signOutFromTheAccountScreenShowsThePhoneLibrary() = runTest(timeout = 30.seconds) {
        val app = FakeLibraries(this, fake, engineJob).also { it.start() }
        app.signIn(ana)
        advanceUntilIdle()
        val vm = AccountViewModel(fake.services, shrinker, sync = app.active.sync, workDispatcher = mainDispatcher, sessions = app.sessions)

        vm.signOut()
        advanceUntilIdle()
        assertTrue(vm.state.value.askKeepLibrary)
        vm.signOut(keepLibrary = false)
        advanceUntilIdle()

        assertTrue(vm.state.value.signedOut)
        assertEquals(SessionState.SignedOut, fake.auth.session.value)
        assertEquals(LibraryId.Local, app.active.id)
        assertEquals(listOf<LibraryId>(LibraryId.Account(ana)), app.deleted)
    }

    @Test
    fun deletingTheAccountShowsThePhoneLibraryAndKeepsTheAccounts() = runTest(timeout = 30.seconds) {
        val app = FakeLibraries(this, fake, engineJob).also { it.start() }
        app.signIn(ana)
        advanceUntilIdle()
        val accountLibrary = app.active
        accountLibrary.addBook("Os Maias")
        advanceUntilIdle()
        val vm = AccountViewModel(fake.services, shrinker, sync = accountLibrary.sync, workDispatcher = mainDispatcher, sessions = app.sessions)

        vm.deleteAccount()
        advanceUntilIdle()

        assertTrue(vm.state.value.deleted)
        assertEquals(LibraryId.Local, app.active.id)
        assertEquals(SyncStatus.Off, accountLibrary.sync!!.status.value)
        assertTrue(app.deleted.isEmpty())
        assertEquals(listOf("Os Maias"), app.stores.getValue(LibraryId.Account(ana)).live().map { it.title })
    }
}
