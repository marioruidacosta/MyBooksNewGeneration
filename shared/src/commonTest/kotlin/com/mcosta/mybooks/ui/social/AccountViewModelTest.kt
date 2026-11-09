package com.mcosta.mybooks.ui.social

import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.FakeBookmarkDao
import com.mcosta.mybooks.data.FakeChallengeDao
import com.mcosta.mybooks.data.FakeEbookDao
import com.mcosta.mybooks.data.FakeQuoteDao
import com.mcosta.mybooks.data.FakeSyncQueueDao
import com.mcosta.mybooks.data.FakeSyncStateDao
import com.mcosta.mybooks.data.db.BookEntity
import com.mcosta.mybooks.data.covers.PhotoShrinker
import com.mcosta.mybooks.social.FakeSocial
import com.mcosta.mybooks.share.SharingStatus
import com.mcosta.mybooks.social.model.SessionState
import com.mcosta.mybooks.social.model.Visibility
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.sync.FakeSyncServer
import com.mcosta.mybooks.sync.LibrarySync
import com.mcosta.mybooks.sync.RemoteApplier
import com.mcosta.mybooks.sync.SyncDatabase
import com.mcosta.mybooks.sync.SyncRecorder
import com.mcosta.mybooks.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountViewModelTest : MainDispatcherTest() {
    private val fake = FakeSocial()
    private val ana = fake.addUser("ana")
    private val carla = fake.addUser("carla")

    private class RecordingShrinker : PhotoShrinker {
        var maxSide: Int? = null
        override fun shrink(photo: ByteArray, maxSide: Int): ByteArray {
            this.maxSide = maxSide
            return byteArrayOf(9, 9)
        }
    }

    private val shrinker = RecordingShrinker()

    private fun vm() = AccountViewModel(fake.services, shrinker, workDispatcher = mainDispatcher)

    @Test
    fun loadsProfileAndBlocked() = runTest {
        fake.signInAs(ana)
        fake.safetyService.block(carla)
        val vm = vm()
        assertEquals("ana", vm.state.value.profile?.username)
        assertEquals(listOf("carla"), vm.state.value.blocked.map { it.username })
    }

    @Test
    fun visibilityChangeIsSaved() = runTest {
        fake.signInAs(ana)
        val vm = vm()
        vm.setVisibility(Visibility.PUBLIC)
        assertEquals(Visibility.PUBLIC, vm.state.value.profile?.visibility)
        assertEquals(Visibility.PUBLIC, fake.profileService.myProfile()?.visibility)
    }

    @Test
    fun displayNameIsSavedTrimmed() = runTest {
        fake.signInAs(ana)
        val vm = vm()
        vm.saveDisplayName("  Ana Rita ")
        assertEquals("Ana Rita", fake.profileService.myProfile()?.displayName)
    }

    @Test
    fun blankDisplayNameRejected() = runTest {
        fake.signInAs(ana)
        val vm = vm()
        vm.saveDisplayName("   ")
        assertEquals("Enter a name", vm.state.value.message)
        assertEquals("Ana", fake.profileService.myProfile()?.displayName)
    }

    @Test
    fun avatarIsShrunkTo512BeforeUpload() = runTest {
        fake.signInAs(ana)
        val vm = vm()
        vm.setAvatar(byteArrayOf(1, 2, 3))
        assertEquals(512, shrinker.maxSide)
        assertContentEquals(byteArrayOf(9, 9), fake.uploadedAvatars.single())
        assertTrue(vm.state.value.profile?.avatarUrl != null)
    }

    @Test
    fun unblockRemovesFromList() = runTest {
        fake.signInAs(ana)
        fake.safetyService.block(carla)
        val vm = vm()
        vm.unblock(carla)
        assertTrue(vm.state.value.blocked.isEmpty())
    }

    @Test
    fun signOutEndsSession() = runTest {
        fake.signInAs(ana)
        val vm = vm()
        vm.signOut()
        assertEquals(SessionState.SignedOut, fake.auth.session.value)
        assertEquals(null, vm.state.value.profile)
        assertTrue(vm.state.value.signedOut)
    }

    @Test
    fun deleteAccountSignsOutAndRemovesProfile() = runTest {
        fake.signInAs(ana)
        val vm = vm()
        vm.deleteAccount()
        assertTrue(vm.state.value.deleted)
        assertEquals(SessionState.SignedOut, fake.auth.session.value)
        fake.signInAs(carla)
        assertEquals(null, fake.profileService.person(ana))
    }

    @Test
    fun offlineShowsMessage() = runTest {
        fake.signInAs(ana)
        val vm = vm()
        fake.offline = true
        vm.setVisibility(Visibility.PUBLIC)
        assertEquals("You're offline", vm.state.value.message)
    }

    @Test
    fun turningSharingOffCallsServerAndUpdatesProfile() = runTest {
        fake.signInAs(ana)
        val forgotten = mutableListOf<String>()
        val sharing = SharingStatus(fake.services, backgroundScope, onActivated = {}, onDisabled = { forgotten += it })
        val vm = AccountViewModel(fake.services, shrinker, sharing = sharing, workDispatcher = mainDispatcher)
        assertTrue(vm.state.value.profile!!.shareActivity)
        vm.setShareActivity(false)
        assertFalse(vm.state.value.profile!!.shareActivity)
        assertFalse(fake.profileService.myProfile()!!.shareActivity)
        assertEquals(listOf(ana), forgotten)
    }

    @Test
    fun turningSharingOnUpdatesProfile() = runTest {
        fake.signInAs(ana)
        fake.profileService.setShareActivity(false)
        val vm = vm()
        vm.setShareActivity(true)
        assertTrue(vm.state.value.profile!!.shareActivity)
    }

    // ---- signing out while the library syncs ----

    /** A synced library on this phone, with one book and one change waiting. */
    private class SyncedLibrary(scope: CoroutineScope) {
        val books = FakeBookDao()
        val queue = FakeSyncQueueDao()
        val state = FakeSyncStateDao()
        private val db = SyncDatabase(books, FakeQuoteDao(), FakeEbookDao(), FakeBookmarkDao(), FakeChallengeDao(), queue, state)
        private val recorder = SyncRecorder(queue, { 0L }, { true }, { it() })
        val sync = LibrarySync(FakeSyncServer(), db, recorder, RemoteApplier(db, recorder), { 0L }, scope)

        suspend fun fill() {
            books.insert(
                BookEntity(
                    isbn = null, title = "Os Maias", authors = "Eça de Queirós", publisher = null, publishedYear = null,
                    pageCount = null, coverUrl = null, description = null, status = ReadingStatus.READ, rating = null,
                    notes = "", dateAdded = 0, dateRead = null, searchText = "os maias", uuid = "x",
                ),
            )
            queue.enqueue("books", "x", 0)
            state.set("seq", "7")
        }
    }

    private val engineJob = SupervisorJob()

    @AfterTest
    fun stopEngines() = engineJob.cancel()

    private suspend fun TestScope.syncedLibrary(): SyncedLibrary {
        val library = SyncedLibrary(CoroutineScope(StandardTestDispatcher(testScheduler) + engineJob))
        library.fill()
        library.sync.start(ana)
        return library
    }

    @Test
    fun signOutWhileSyncingAsksWhetherToKeepTheLibrary() = runTest {
        fake.signInAs(ana)
        val library = syncedLibrary()
        val vm = AccountViewModel(fake.services, shrinker, sync = library.sync, workDispatcher = mainDispatcher)

        vm.signOut()

        assertTrue(vm.state.value.askKeepLibrary)
        assertEquals(SessionState.SignedIn(ana, null), fake.auth.session.value)
        assertFalse(vm.state.value.signedOut)

        vm.cancelSignOut()
        assertFalse(vm.state.value.askKeepLibrary)
        assertEquals(SessionState.SignedIn(ana, null), fake.auth.session.value)
    }

    @Test
    fun signOutKeepLeavesLibraryClearsQueue() = runTest {
        fake.signInAs(ana)
        val library = syncedLibrary()
        val vm = AccountViewModel(fake.services, shrinker, sync = library.sync, workDispatcher = mainDispatcher)

        vm.signOut()
        vm.signOut(keepLibrary = true)

        assertEquals(SessionState.SignedOut, fake.auth.session.value)
        assertTrue(vm.state.value.signedOut)
        assertFalse(vm.state.value.askKeepLibrary)
        assertEquals(listOf("x"), library.books.rows.value.values.map { it.uuid })
        assertTrue(library.queue.rows.value.isEmpty())
        assertTrue(library.state.rows.isEmpty())
        assertEquals(SyncStatus.Off, library.sync.status.value)
        // Sign-out is over: sync can start again (the next sign-in).
        library.sync.start(ana)
        assertEquals(SyncStatus.Syncing, library.sync.status.value)
    }

    @Test
    fun signOutRemoveErasesLibrary() = runTest {
        fake.signInAs(ana)
        val library = syncedLibrary()
        val vm = AccountViewModel(fake.services, shrinker, sync = library.sync, workDispatcher = mainDispatcher)

        vm.signOut()
        vm.signOut(keepLibrary = false)

        assertEquals(SessionState.SignedOut, fake.auth.session.value)
        assertTrue(vm.state.value.signedOut)
        assertTrue(library.books.rows.value.isEmpty())
        assertTrue(library.queue.rows.value.isEmpty())
        assertEquals(SyncStatus.Off, library.sync.status.value)
    }

    @Test
    fun signOutWithSyncOffSignsOutAtOnce() = runTest {
        fake.signInAs(ana)
        val library = SyncedLibrary(CoroutineScope(StandardTestDispatcher(testScheduler) + engineJob))
        library.fill()
        val vm = AccountViewModel(fake.services, shrinker, sync = library.sync, workDispatcher = mainDispatcher)

        vm.signOut()

        assertFalse(vm.state.value.askKeepLibrary)
        assertTrue(vm.state.value.signedOut)
        assertEquals(SessionState.SignedOut, fake.auth.session.value)
        assertEquals(1, library.books.rows.value.size)
        assertEquals(1, library.queue.rows.value.size)
    }
}
