@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.mcosta.mybooks.library

import com.mcosta.mybooks.data.db.BookEntity
import com.mcosta.mybooks.data.db.BookmarkEntity
import com.mcosta.mybooks.data.db.ChallengeEntity
import com.mcosta.mybooks.data.db.EbookEntity
import com.mcosta.mybooks.data.db.QuoteEntity
import com.mcosta.mybooks.domain.EbookFormat
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.social.FakeSocial
import com.mcosta.mybooks.sync.SyncException
import com.mcosta.mybooks.sync.SyncStatus
import com.mcosta.mybooks.sync.SyncTable
import com.mcosta.mybooks.sync.toRow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okio.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** An account's first sign-in on this phone asks to add the phone's books; they can also be added later. */
class LibrariesOfferTest {
    private val engines = SupervisorJob()

    @AfterTest
    fun stopEngines() = engines.cancel()

    private val social = FakeSocial()
    private val a = social.addUser("a")
    private val b = social.addUser("b")
    private val accountA = LibraryId.Account(a)
    private val accountB = LibraryId.Account(b)

    /** The app, asking about the phone's books at an account's first sign-in. */
    private suspend fun TestScope.app(offers: Boolean = true): FakeLibraries =
        FakeLibraries(this, social, engines).also {
            if (offers) it.offers()
            it.start()
        }

    private fun cloudTitles(owner: String): Set<String> = social.sync.stored(owner).values
        .filter { it.table == SyncTable.BOOKS && (it.fields["deleted"] as? JsonPrimitive)?.booleanOrNull != true }
        .mapTo(mutableSetOf()) { (it.fields["title"] as JsonPrimitive).contentOrNull.orEmpty() }

    private fun FakeLibraryStore.titles(): Set<String> = live().mapTo(mutableSetOf()) { it.title }

    private fun FakeLibraries.asked(id: LibraryId): Boolean = stores.getValue(id).state.rows[ASKED_LOCAL_BOOKS_KEY] != null

    private fun FakeLibraries.write(path: Path, text: String) {
        fs.createDirectories(path.parent!!)
        fs.write(path) { writeUtf8(text) }
    }

    /** A book already in [owner]'s cloud library (added on another device). */
    private suspend fun inTheCloud(owner: String, title: String) {
        social.sync.owner = owner
        val book = BookEntity(
            isbn = null, title = title, authors = "Eça de Queirós", publisher = null, publishedYear = null, pageCount = null,
            coverUrl = null, description = null, status = ReadingStatus.READ, rating = null, notes = "", dateAdded = 0,
            dateRead = null, searchText = title.lowercase(), fieldTimes = """{"title":1}""",
        )
        social.sync.push(listOf(book.toRow(null)))
    }

    private fun offline() {
        social.sync.failNext = SyncException(SyncException.Kind.OFFLINE, "offline")
        social.sync.failTimes = 1_000
    }

    private fun online() {
        social.sync.failNext = null
        social.sync.failTimes = 1
    }

    @Test
    fun addThemMovesEverything() = runTest(timeout = 30.seconds) {
        val app = app()
        val phone = app.active
        val maias = phone.addBook("Os Maias", isbn = "9789720049575", coverUrl = "cover:maias.jpg")
        phone.addBook("A Relíquia")
        phone.store.quotes.insert(QuoteEntity(bookId = maias.id, text = "Tudo é bom.", page = 12, dateAdded = 1))
        phone.store.bookmarks.insert(BookmarkEntity(bookId = maias.id, position = "{}", label = "Capítulo I", dateAdded = 1))
        phone.store.ebooks.upsert(EbookEntity(maias.id, EbookFormat.EPUB, "os-maias.epub", 4, 1, uuid = maias.uuid))
        phone.store.challenges.upsert(ChallengeEntity(2026, 20))
        val photo = app.files.coversDir(LibraryId.Local) / "maias.jpg"
        val ebook = app.files.ebooksDir(LibraryId.Local) / "${maias.id}.epub"
        app.write(photo, "photo")
        app.write(ebook, "epub")

        app.signIn(b)
        advanceUntilIdle()

        // The account's own library is open, and waits for the answer: nothing syncs yet.
        assertEquals(AddBooksOffer(accountB, 2), app.libraries.offer.value)
        assertEquals(accountB, app.active.id)
        assertEquals(SyncStatus.Off, app.active.sync!!.status.value)
        assertTrue(social.sync.calls.isEmpty())

        app.libraries.addPhoneBooks()
        advanceUntilIdle()

        assertNull(app.libraries.offer.value)
        val account = app.stores.getValue(accountB)
        assertEquals(setOf("Os Maias", "A Relíquia"), account.titles())
        val moved = account.live().single { it.title == "Os Maias" }
        assertEquals(maias.uuid, moved.uuid)
        assertEquals(listOf(moved.id), account.quotes.rows.value.values.map { it.bookId })
        assertEquals(listOf(moved.id), account.bookmarks.rows.value.values.map { it.bookId })
        assertEquals(moved.uuid, account.ebooks.rows.value.getValue(moved.id).uuid)
        assertEquals(mapOf(2026 to 20), account.challenges.rows.value.mapValues { it.value.goal })
        assertEquals("photo", app.fs.read(app.files.coversDir(accountB) / "maias.jpg") { readUtf8() })
        assertEquals("epub", app.fs.read(app.files.ebooksDir(accountB) / "${moved.id}.epub") { readUtf8() })
        // The phone's library is left empty.
        assertTrue(app.stores.getValue(LibraryId.Local).empty())
        assertFalse(app.fs.exists(photo))
        assertFalse(app.fs.exists(ebook))
        // Then the first sign-in merge sends them to the account.
        assertTrue(app.asked(accountB))
        assertIs<SyncStatus.Synced>(app.active.sync!!.status.value)
        assertEquals(setOf("Os Maias", "A Relíquia"), cloudTitles(b))
    }

    @Test
    fun notNowLeavesThemAndPulls() = runTest(timeout = 30.seconds) {
        inTheCloud(b, "A Cidade e as Serras")
        val app = app()
        app.active.addBook("Mensagem")

        app.signIn(b)
        advanceUntilIdle()
        assertEquals(AddBooksOffer(accountB, 1), app.libraries.offer.value)

        app.libraries.notNow()
        advanceUntilIdle()

        assertNull(app.libraries.offer.value)
        assertEquals(setOf("Mensagem"), app.stores.getValue(LibraryId.Local).titles())
        assertEquals(setOf("A Cidade e as Serras"), app.active.store.titles())
        assertEquals(setOf("A Cidade e as Serras"), cloudTitles(b))
        assertIs<SyncStatus.Synced>(app.active.sync!!.status.value)
        assertTrue(app.asked(accountB))
    }

    @Test
    fun askedOncePerAccount() = runTest(timeout = 30.seconds) {
        val app = app()
        app.active.addBook("Mensagem")
        app.signIn(b)
        advanceUntilIdle()
        app.libraries.notNow()
        advanceUntilIdle()
        app.signOut(keep = true)
        advanceUntilIdle()

        app.signIn(b)
        advanceUntilIdle()

        assertNull(app.libraries.offer.value)
        assertIs<SyncStatus.Synced>(app.active.sync!!.status.value)

        // Another account is asked for itself.
        app.signOut(keep = true)
        advanceUntilIdle()
        app.signIn(a)
        advanceUntilIdle()
        assertEquals(AddBooksOffer(accountA, 1), app.libraries.offer.value)
    }

    @Test
    fun noPhoneBooksNoQuestion() = runTest(timeout = 30.seconds) {
        val app = app()

        app.signIn(b)
        advanceUntilIdle()

        assertNull(app.libraries.offer.value)
        assertIs<SyncStatus.Synced>(app.active.sync!!.status.value)
    }

    @Test
    fun firstSignInOfflineKeepsOfferAndWaits() = runTest(timeout = 30.seconds) {
        val app = app()
        app.active.addBook("Mensagem")
        offline()

        app.signIn(b)
        runCurrent()

        // Asking needs no network: the question is up, and sync waits for the answer.
        assertEquals(AddBooksOffer(accountB, 1), app.libraries.offer.value)
        assertEquals(SyncStatus.Off, app.active.sync!!.status.value)
        assertTrue(social.sync.calls.isEmpty())

        app.libraries.addPhoneBooks()
        runCurrent()

        assertEquals(setOf("Mensagem"), app.active.store.titles())
        assertIs<SyncStatus.Offline>(app.active.sync!!.status.value)

        online()
        app.active.sync!!.connectivityRestored()
        runCurrent()

        assertIs<SyncStatus.Synced>(app.active.sync!!.status.value)
        assertEquals(setOf("Mensagem"), cloudTitles(b))
    }

    @Test
    fun laterAddFromSettingsMovesAndQueues() = runTest(timeout = 30.seconds) {
        val app = app()
        app.active.addBook("Mensagem")
        app.active.addBook("Os Lusíadas")
        val phoneUuids = app.active.store.live().mapTo(mutableSetOf()) { it.uuid }
        app.signIn(b)
        advanceUntilIdle()
        app.libraries.notNow()
        advanceUntilIdle()
        assertIs<SyncStatus.Synced>(app.active.sync!!.status.value)
        assertEquals(2, app.libraries.phoneBooks())

        // Settings → Account → "Add this phone's books", while the network is down.
        offline()
        app.libraries.addPhoneBooks()
        runCurrent()

        val account = app.active.store
        assertEquals(setOf("Mensagem", "Os Lusíadas"), account.titles())
        assertEquals(phoneUuids, account.queue.rows.value.filter { it.table == SyncTable.BOOKS.wire }.mapTo(mutableSetOf()) { it.rowId })
        assertTrue(app.stores.getValue(LibraryId.Local).empty())
        assertEquals(0, app.libraries.phoneBooks())

        online()
        app.active.sync!!.connectivityRestored()
        runCurrent()

        assertEquals(setOf("Mensagem", "Os Lusíadas"), cloudTitles(b))
        assertTrue(account.queue.rows.value.isEmpty())
    }

    @Test
    fun theSessionEndingDuringTheOfferTakesItAwayAtOnce() = runTest(timeout = 30.seconds) {
        val app = app()
        app.active.addBook("Mensagem")
        app.signIn(b)
        advanceUntilIdle()
        val held = app.active
        assertNotNull(app.libraries.offer.value)

        // The session ends (signed out elsewhere, or expired) while the question is open.
        social.auth.signOut()
        runCurrent()

        assertNull(app.libraries.offer.value)
        // An answer that comes too late does nothing: it's asked again at the next sign-in.
        app.libraries.notNow()
        advanceUntilIdle()
        assertFalse(app.asked(accountB))
        assertEquals(SyncStatus.Off, held.sync!!.status.value)
        assertTrue(social.sync.calls.isEmpty())

        app.signIn(b)
        advanceUntilIdle()
        assertEquals(AddBooksOffer(accountB, 1), app.libraries.offer.value)

        // Signing out from Settings during the question works at once too.
        app.signOut(keep = true)
        advanceUntilIdle()

        assertNull(app.libraries.offer.value)
        assertEquals(LibraryId.Local, app.active.id)
        assertEquals(setOf("Mensagem"), app.active.store.titles())
        assertEquals(SyncStatus.Off, held.sync!!.status.value)
        assertTrue(social.sync.calls.isEmpty())
    }

    @Test
    fun anotherAccountSigningInDuringTheOfferIsAskedForItself() = runTest(timeout = 30.seconds) {
        val app = app()
        app.active.addBook("Mensagem")
        app.signIn(b)
        advanceUntilIdle()
        val held = app.active

        social.auth.signOut()
        app.signIn(a)
        advanceUntilIdle()

        assertEquals(AddBooksOffer(accountA, 1), app.libraries.offer.value)
        assertEquals(accountA, app.active.id)
        assertEquals(SyncStatus.Off, held.sync!!.status.value)
        app.libraries.addPhoneBooks()
        advanceUntilIdle()
        assertEquals(setOf("Mensagem"), app.active.store.titles())
        assertEquals(emptySet(), app.stores.getValue(accountB).titles())
        assertEquals(setOf("Mensagem"), cloudTitles(a))
        assertEquals(emptySet(), cloudTitles(b))
    }

    @Test
    fun deletingTheAccountMovesItsBooksToThePhoneLibrary() = runTest(timeout = 30.seconds) {
        val app = app(offers = false)
        app.active.addBook("Mensagem", isbn = "9789720049100", notes = "Lido na escola.")
        app.signIn(a)
        advanceUntilIdle()
        app.active.addBook("Mensagem", isbn = "9789720049100", notes = "Relido em 2026.")
        app.active.addBook("Os Maias", isbn = "9789720049575")
        val accountFiles = app.putFiles(accountA)
        advanceUntilIdle()

        app.sessions.signOut(OnSignOut.MOVE_TO_PHONE) { social.auth.signOut() }
        advanceUntilIdle()

        assertEquals(LibraryId.Local, app.active.id)
        val phone = app.stores.getValue(LibraryId.Local)
        assertEquals(setOf("Mensagem", "Os Maias"), phone.titles())
        val mensagem = phone.live().single { it.title == "Mensagem" }
        assertTrue("Lido na escola." in mensagem.notes && "Relido em 2026." in mensagem.notes)
        assertTrue(phone.queue.rows.value.isEmpty())
        // The account's library is gone, files and all.
        assertEquals(listOf<LibraryId>(accountA), app.deleted)
        assertNull(app.stores[accountA])
        assertTrue(accountFiles.none { app.fs.exists(it) })
    }

    @Test
    fun aFailedMoveKeepsTheAccountLibrary() = runTest(timeout = 30.seconds) {
        val app = app(offers = false)
        app.signIn(a)
        advanceUntilIdle()
        app.active.addBook("Os Maias")
        advanceUntilIdle()
        app.stores.getValue(LibraryId.Local).ebooks.failUpsert = true
        app.stores.getValue(accountA).ebooks.upsert(
            EbookEntity(app.active.store.live().single().id, EbookFormat.EPUB, "os-maias.epub", 4, 1),
        )

        app.sessions.signOut(OnSignOut.MOVE_TO_PHONE) { social.auth.signOut() }
        advanceUntilIdle()

        // Signed out, on the phone's library; nothing lost: the account's library stays for another try.
        assertEquals(LibraryId.Local, app.active.id)
        assertTrue(app.deleted.isEmpty())
        assertEquals(setOf("Os Maias"), app.stores.getValue(accountA).titles())
    }
}
