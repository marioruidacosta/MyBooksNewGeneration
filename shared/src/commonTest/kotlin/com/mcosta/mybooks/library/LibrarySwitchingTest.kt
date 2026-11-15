@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.mcosta.mybooks.library

import com.mcosta.mybooks.social.FakeSocial
import com.mcosta.mybooks.sync.SyncException
import com.mcosta.mybooks.sync.SyncStatus
import com.mcosta.mybooks.sync.SyncTable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Each account signing in gets its own library on this phone; signing out shows the phone's own. */
class LibrarySwitchingTest {
    private val engines = SupervisorJob()

    @AfterTest
    fun stopEngines() = engines.cancel()

    private val social = FakeSocial()
    private val a = social.addUser("a")
    private val b = social.addUser("b")
    private val accountA = LibraryId.Account(a)
    private val accountB = LibraryId.Account(b)

    private suspend fun TestScope.app(): FakeLibraries = FakeLibraries(this, social, engines).also { it.start() }

    /** Live book titles in [owner]'s cloud library. */
    private fun cloudTitles(owner: String): Set<String> = social.sync.stored(owner).values
        .filter { it.table == SyncTable.BOOKS && (it.fields["deleted"] as? JsonPrimitive)?.booleanOrNull != true }
        .mapTo(mutableSetOf()) { (it.fields["title"] as JsonPrimitive).contentOrNull.orEmpty() }

    private fun FakeLibrarySpace.titles(): Set<String> = store.live().mapTo(mutableSetOf()) { it.title }

    private fun offline() {
        social.sync.failNext = SyncException(SyncException.Kind.OFFLINE, "offline")
        social.sync.failTimes = 1_000
    }

    private fun online() {
        social.sync.failNext = null
        social.sync.failTimes = 1
    }

    @Test
    fun otherAccountsBooksNeverReachMine() = runTest(timeout = 30.seconds) {
        val app = app()
        app.signIn(b)
        advanceUntilIdle()
        assertEquals(accountB, app.active.id)
        app.active.addBook("Os Maias")
        app.active.addBook("A Relíquia")
        advanceUntilIdle()
        assertEquals(setOf("Os Maias", "A Relíquia"), cloudTitles(b))

        app.signOut(keep = true)
        advanceUntilIdle()
        app.signIn(a)
        advanceUntilIdle()

        assertEquals(accountA, app.active.id)
        assertIs<SyncStatus.Synced>(app.active.sync!!.status.value)
        assertEquals(emptySet(), app.active.titles())
        assertEquals(emptySet(), cloudTitles(a))
        assertEquals(setOf("Os Maias", "A Relíquia"), cloudTitles(b))
    }

    @Test
    fun signingBackInRestoresOwnLibraryOffline() = runTest(timeout = 30.seconds) {
        val app = app()
        app.signIn(b)
        advanceUntilIdle()
        app.active.addBook("Os Maias")
        app.active.addBook("A Relíquia")
        advanceUntilIdle()
        // Offline from here: the next change waits in B's queue.
        offline()
        app.active.addBook("O Primo Basílio")
        runCurrent()
        assertEquals(1, app.stores.getValue(accountB).queue.rows.value.size)
        app.signOut(keep = true)
        runCurrent()
        app.signIn(a)
        runCurrent()
        app.signOut(keep = true)
        runCurrent()

        app.signIn(b)
        runCurrent()

        // B's own library at once, offline, with its change still waiting.
        assertEquals(accountB, app.active.id)
        assertEquals(setOf("Os Maias", "A Relíquia", "O Primo Basílio"), app.active.titles())
        assertEquals(1, app.active.store.queue.rows.value.size)
        assertIs<SyncStatus.Offline>(app.active.sync!!.status.value)

        online()
        app.active.sync!!.connectivityRestored()
        runCurrent()

        assertTrue(app.active.store.queue.rows.value.isEmpty())
        assertEquals(setOf("Os Maias", "A Relíquia", "O Primo Basílio"), cloudTitles(b))
        assertEquals(emptySet(), cloudTitles(a))
    }

    @Test
    fun keepShowsPhoneLibraryAndKeepsFiles() = runTest(timeout = 30.seconds) {
        val app = app()
        app.active.addBook("Mensagem")
        app.signIn(b)
        advanceUntilIdle()
        app.active.addBook("Os Maias")
        val bFiles = app.putFiles(accountB)
        advanceUntilIdle()

        app.signOut(keep = true)
        advanceUntilIdle()

        assertEquals(LibraryId.Local, app.active.id)
        assertEquals(setOf("Mensagem"), app.active.titles())
        assertEquals("local", app.settings.values["library.active"])
        // B's library stays on the phone, hidden, with its files.
        assertTrue(app.deleted.isEmpty())
        assertEquals(setOf("Os Maias"), app.stores.getValue(accountB).live().mapTo(mutableSetOf()) { it.title })
        assertTrue(bFiles.all { app.fs.exists(it) })
    }

    @Test
    fun removeDeletesOnlyThatAccount() = runTest(timeout = 30.seconds) {
        val app = app()
        app.active.addBook("Mensagem")
        val localFiles = app.putFiles(LibraryId.Local)
        app.signIn(a)
        advanceUntilIdle()
        app.active.addBook("A Cidade e as Serras")
        val aFiles = app.putFiles(accountA)
        app.signOut(keep = true)
        advanceUntilIdle()
        app.signIn(b)
        advanceUntilIdle()
        app.active.addBook("Os Maias")
        val bFiles = app.putFiles(accountB)
        advanceUntilIdle()

        app.signOut(keep = false)
        advanceUntilIdle()

        assertEquals(LibraryId.Local, app.active.id)
        assertEquals(listOf<LibraryId>(accountB), app.deleted)
        assertNull(app.stores[accountB])
        assertTrue(bFiles.none { app.fs.exists(it) })
        assertFalse(app.fs.exists(app.files.coversDir(accountB).parent!!))
        // Nothing else is touched: the phone's library, A's library, and B's cloud.
        assertEquals(setOf("Mensagem"), app.active.titles())
        assertTrue((localFiles + aFiles).all { app.fs.exists(it) })
        assertEquals(setOf("A Cidade e as Serras"), app.stores.getValue(accountA).live().mapTo(mutableSetOf()) { it.title })
        assertEquals(setOf("Os Maias"), cloudTitles(b))
    }

    @Test
    fun removeThenSignInAgainStartsEmptyAndPulls() = runTest(timeout = 30.seconds) {
        val app = app()
        app.signIn(b)
        advanceUntilIdle()
        app.active.addBook("Os Maias")
        app.active.addBook("A Relíquia")
        advanceUntilIdle()
        val removed = app.active.store
        app.signOut(keep = false)
        advanceUntilIdle()
        val merges = social.sync.calls.count { it.first == "merge" }

        app.signIn(b)
        advanceUntilIdle()

        assertEquals(accountB, app.active.id)
        // A new, empty library: nothing came back from disk, and the first merge's empty path pulled the cloud's.
        assertNotSame(removed, app.active.store)
        assertEquals(merges, social.sync.calls.count { it.first == "merge" })
        assertEquals(setOf("Os Maias", "A Relíquia"), app.active.titles())
        assertIs<SyncStatus.Synced>(app.active.sync!!.status.value)
    }

    @Test
    fun localLibraryNeverSyncsOrPublishes() = runTest(timeout = 30.seconds) {
        val app = app()
        assertNull(app.active.sync)
        app.active.addBook("Mensagem")
        // Signed in, but the account's library can't be opened: the phone's library is shown, and stays local.
        app.failing = accountA
        app.signIn(a)
        advanceUntilIdle()
        assertEquals(LibraryId.Local, app.active.id)

        app.active.addBook("Os Lusíadas")
        advanceUntilIdle()

        assertNull(app.active.sync)
        assertFalse(app.active.recorder.enabled())
        assertTrue(app.active.store.queue.rows.value.isEmpty())
        assertTrue(app.active.store.shares.rows.value.isEmpty())
        assertTrue(social.sync.calls.isEmpty())
        assertTrue(social.published.isEmpty())
        // Nothing was deleted either.
        assertTrue(app.deleted.isEmpty())
    }

    @Test
    fun oldSpaceClosedOnlyAfterSyncStopped() = runTest(timeout = 30.seconds) {
        val app = app()
        app.signIn(b)
        advanceUntilIdle()
        val old = app.active
        // A screen shows B's library.
        val screen = app.libraries.hold(old)!!

        app.signOut(keep = true)
        advanceUntilIdle()

        // Switched, B's sync stopped at once, but its database stays open while the screen still shows it.
        assertEquals(LibraryId.Local, app.active.id)
        assertEquals(SyncStatus.Off, old.sync!!.status.value)
        assertFalse(old.closed)
        // A library no longer active can't be held.
        assertNull(app.libraries.hold(old))

        screen.release()
        advanceUntilIdle()

        assertTrue(old.closed)
        assertEquals(listOf("close $b: sync Off"), app.log.filter { it.startsWith("close $b") })

        // Remove waits for its database to close before deleting it.
        app.signIn(b)
        advanceUntilIdle()
        val again = app.active
        val shown = app.libraries.hold(again)!!
        val signingOut = launch { app.signOut(keep = false) }
        advanceUntilIdle()
        assertFalse(again.closed)
        assertTrue(app.deleted.isEmpty())
        shown.release()
        advanceUntilIdle()
        assertTrue(signingOut.isCompleted)
        assertTrue(again.closed)
        assertEquals(listOf<LibraryId>(accountB), app.deleted)
    }

    @Test
    fun expiryKeepsAccountLibraryActive() = runTest(timeout = 30.seconds) {
        val app = app()
        app.signIn(b)
        advanceUntilIdle()
        app.active.addBook("Os Maias")
        advanceUntilIdle()
        val merges = social.sync.calls.count { it.first == "merge" }

        // The session ends without the user asking (the refresh token was rejected).
        social.auth.signOut()
        advanceUntilIdle()

        assertEquals(accountB, app.active.id)
        assertEquals(SyncStatus.SignInAgain, app.active.sync!!.status.value)
        assertTrue(app.active.recorder.enabled())
        app.active.addBook("A Relíquia")
        assertEquals(1, app.active.store.queue.rows.value.size)

        // Signing in again carries on: no second merge, and the change goes up.
        app.signIn(b)
        advanceUntilIdle()
        assertIs<SyncStatus.Synced>(app.active.sync!!.status.value)
        assertEquals(merges, social.sync.calls.count { it.first == "merge" })
        assertTrue(app.active.store.queue.rows.value.isEmpty())
        assertEquals(setOf("Os Maias", "A Relíquia"), cloudTitles(b))
    }

    // ---- starting the app ----

    @Test
    fun accountSyncStartsOnlyAfterItsPreferencesLoad() = runTest(timeout = 30.seconds) {
        val app = FakeLibraries(this, social, engines)
        app.settings.values["library.active"] = b
        val gate = CompletableDeferred<Unit>()
        app.openGate = gate
        app.signIn(b)

        app.start()
        advanceUntilIdle()

        // The last library is shown at once; its preferences are still loading, and nothing syncs yet.
        assertEquals(accountB, app.active.id)
        assertFalse(app.active.opened)
        assertEquals(SyncStatus.Off, app.active.sync!!.status.value)
        assertTrue(social.sync.calls.isEmpty())

        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(app.active.opened)
        assertIs<SyncStatus.Synced>(app.active.sync!!.status.value)
    }

    @Test
    fun sessionExpiredWhileTheAppWasClosedShowsSignInAgain() = runTest(timeout = 30.seconds) {
        val app = FakeLibraries(this, social, engines)
        app.settings.values["library.active"] = b
        // Synced in an earlier run; this time the stored session can't be refreshed.
        app.stores[accountB] = FakeLibraryStore().apply { state.rows["seq"] = "5" }

        app.start()
        advanceUntilIdle()

        assertEquals(accountB, app.active.id)
        assertEquals(SyncStatus.SignInAgain, app.active.sync!!.status.value)
        assertTrue(app.active.recorder.enabled())
    }

    @Test
    fun editsWhileTheSessionLoadsAreQueuedForTheAccount() = runTest(timeout = 30.seconds) {
        val app = FakeLibraries(this, social, engines)
        app.settings.values["library.active"] = b
        // B's library joined the cloud in an earlier run; the stored session isn't restored yet.
        app.stores[accountB] = FakeLibraryStore().apply { state.rows["merged:$b"] = "1" }
        social.loading()
        app.start()
        advanceUntilIdle()

        app.active.addBook("Os Maias")
        assertEquals(SyncStatus.Off, app.active.sync!!.status.value)
        assertEquals(1, app.active.store.queue.rows.value.size)

        app.signIn(b)
        advanceUntilIdle()
        assertTrue(app.active.store.queue.rows.value.isEmpty())
        assertEquals(setOf("Os Maias"), cloudTitles(b))
    }

    @Test
    fun anotherAccountSigningInSwitchesStraightToItsLibrary() = runTest(timeout = 30.seconds) {
        val app = app()
        app.signIn(b)
        advanceUntilIdle()
        app.active.addBook("Os Maias")
        advanceUntilIdle()
        // B's session expired, and A signs in on the "Sign in again" screen.
        social.auth.signOut()
        advanceUntilIdle()
        app.active.addBook("A Relíquia")

        app.signIn(a)
        advanceUntilIdle()

        assertEquals(accountA, app.active.id)
        assertEquals(emptySet(), app.active.titles())
        assertEquals(emptySet(), cloudTitles(a))
        // B's change waits in B's library for B's next sign-in.
        assertEquals(1, app.stores.getValue(accountB).queue.rows.value.size)
    }

    @Test
    fun syncNowNeverStartsALibraryForAnotherAccount() = runTest(timeout = 30.seconds) {
        val app = app()
        app.signIn(b)
        advanceUntilIdle()
        val bSync = app.active.sync!!
        bSync.stop()
        // A signs in: until the switch, B's library is still the active one.
        social.sync.owner = a
        social.signInAs(a)

        app.sessions.syncNow()

        assertEquals(SyncStatus.Off, bSync.status.value)
        advanceUntilIdle()
        assertEquals(accountA, app.active.id)
        assertEquals(emptySet(), cloudTitles(a))
    }
}
