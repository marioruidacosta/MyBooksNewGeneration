package com.mcosta.mybooks.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.data.ReadingEvents
import com.mcosta.mybooks.data.authors.GoogleBooksAuthorSearch
import com.mcosta.mybooks.data.db.AppDatabase
import com.mcosta.mybooks.data.db.toEntity
import com.mcosta.mybooks.data.lookup.LookupChain
import com.mcosta.mybooks.data.lookup.lookupHttpClient
import com.mcosta.mybooks.data.releases.ReleasesClient
import com.mcosta.mybooks.data.releases.ReleasesRepository
import com.mcosta.mybooks.data.suggestions.SubjectSearch
import com.mcosta.mybooks.domain.Book
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/** The one-time upgrade of today's single library, with real files and a real Room database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryUpgradeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fs = FileSystem.SYSTEM
    private val files = LibraryFiles(fs, context.filesDir.toOkioPath() / "upgrade-test")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class MemoryPreferences : Preferences {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String) {
            values[key] = value
        }
    }

    private val devicePrefs = MemoryPreferences()

    private val owner = LibraryId.Account("owner-1")

    private fun dbPath(id: LibraryId): Path = libraryDatabaseFile(context, id).toOkioPath()

    private fun upgrade(open: (LibraryId) -> AppDatabase = { openLibraryDatabase(context, it) }) =
        LibraryUpgrade(devicePrefs, files, ::dbPath, open)

    @After
    fun tearDown() {
        scope.cancel()
        for (id in listOf(LibraryId.Local, owner, LibraryId.Account("acc-a"), LibraryId.Account("acc-b"))) {
            val db = dbPath(id)
            for (suffix in listOf("", "-wal", "-shm", "-journal")) fs.delete(db.parent!! / (db.name + suffix))
        }
        fs.deleteRecursively(files.filesDir)
    }

    /** Today's phone: one `mybooks.db` with [titles], merged into [owners] (in that order), a cover and two ebooks. */
    private suspend fun seedPhone(vararg owners: String, titles: List<String> = listOf("Dune", "Emma")) {
        val db = openLibraryDatabase(context, LibraryId.Local)
        titles.forEach { db.bookDao().insert(Book(title = it, coverUrl = "cover:c1.jpg").toEntity()) }
        // A deleted book doesn't count.
        db.bookDao().insert(Book(title = "Gone").toEntity().copy(deleted = true))
        owners.forEach { db.syncStateDao().set("merged:$it", "1") }
        db.close()
        writeFile(files.coversDir(LibraryId.Local) / "c1.jpg", "jpeg")
        writeFile(files.ebooksDir(LibraryId.Local) / "1.epub", "epub-1")
        writeFile(files.ebooksDir(LibraryId.Local) / "2.pdf", "pdf-2")
        devicePrefs.values["home.pinnedTags"] = "Poetry"
        devicePrefs.values["suggestions.hidden"] = "9789720046130"
    }

    private fun writeFile(path: Path, text: String) {
        fs.createDirectories(path.parent!!)
        fs.write(path) { writeUtf8(text) }
    }

    private fun read(path: Path) = fs.read(path) { readUtf8() }

    private suspend fun <T> withDb(id: LibraryId, block: suspend (AppDatabase) -> T): T {
        val db = openLibraryDatabase(context, id)
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    private suspend fun books(id: LibraryId) = withDb(id) { db -> db.bookDao().getAll().map { it.title }.sorted() }

    private suspend fun libraryPref(id: LibraryId, key: String) = withDb(id) { db ->
        DbPreferences(db.syncStateDao(), scope).also { it.load() }.get(key)
    }

    private fun regularFiles(dir: Path) =
        fs.listOrNull(dir).orEmpty().filter { fs.metadataOrNull(it)?.isRegularFile == true }.map { it.name }.sorted()

    @Test
    fun syncedLibraryMovesToItsAccount() = runTest(timeout = 60.seconds) {
        seedPhone("owner-1")

        val result = upgrade().run(signedInUser = null)

        assertEquals(UpgradeResult.MovedTo("owner-1"), result)
        assertEquals(listOf("Dune", "Emma"), books(owner))
        assertEquals("jpeg", read(files.coversDir(owner) / "c1.jpg"))
        assertEquals(listOf("1.epub", "2.pdf"), regularFiles(files.ebooksDir(owner)))
        assertEquals("epub-1", read(files.ebooksDir(owner) / "1.epub"))
        assertEquals("Poetry", libraryPref(owner, "home.pinnedTags"))
        assertEquals("9789720046130", libraryPref(owner, "suggestions.hidden"))
        // The no-account library is new and empty.
        assertEquals(emptyList<String>(), books(LibraryId.Local))
        assertFalse(withDb(LibraryId.Local) { it.syncStateDao().anyMerged() })
        assertEquals(emptyList<String>(), regularFiles(files.coversDir(LibraryId.Local)))
        assertEquals(emptyList<String>(), regularFiles(files.ebooksDir(LibraryId.Local)))
        assertEquals("owner-1", devicePrefs.values["library.active"])
        assertTrue(devicePrefs.values.containsKey("library.upgraded"))
    }

    @Test
    fun neverSyncedStaysLocal() = runTest(timeout = 60.seconds) {
        seedPhone()

        val result = upgrade().run(signedInUser = "owner-1")

        assertEquals(UpgradeResult.StayedLocal, result)
        assertEquals(listOf("Dune", "Emma"), books(LibraryId.Local))
        assertEquals("jpeg", read(files.coversDir(LibraryId.Local) / "c1.jpg"))
        assertEquals(listOf("1.epub", "2.pdf"), regularFiles(files.ebooksDir(LibraryId.Local)))
        assertFalse(fs.exists(dbPath(owner)))
        assertFalse(fs.exists(files.coversDir(owner)))
        assertNull(devicePrefs.values["library.active"])
        assertTrue(devicePrefs.values.containsKey("library.upgraded"))
    }

    @Test
    fun localPrefsMoveIntoItsDatabase() = runTest(timeout = 60.seconds) {
        seedPhone()
        upgrade().run(signedInUser = null)
        // From now on the device settings' copies are no longer read.
        devicePrefs.values["home.pinnedTags"] = "Stale"
        devicePrefs.values["suggestions.hidden"] = "stale"

        // The next start opens the no-account library as the app does.
        val space = LibrarySpace(LibraryId.Local, openLibraryDatabase(context, LibraryId.Local), files, device(), scope)
        space.open()

        assertEquals(listOf("Poetry"), space.pinnedTags.tags.value)
        assertEquals(setOf("9789720046130"), space.hiddenSuggestions.keys.value)
        space.close()
    }

    @Test
    fun twoMergedKeysUsesSignedInUser() = runTest(timeout = 60.seconds) {
        seedPhone("acc-a", "acc-b")

        val result = upgrade().run(signedInUser = "acc-a")

        assertEquals(UpgradeResult.MovedTo("acc-a"), result)
        assertEquals(listOf("Dune", "Emma"), books(LibraryId.Account("acc-a")))
        assertFalse(fs.exists(dbPath(LibraryId.Account("acc-b"))))
        assertEquals("acc-a", devicePrefs.values["library.active"])
    }

    @Test
    fun twoMergedKeysNoSessionUsesLastRecorded() = runTest(timeout = 60.seconds) {
        // Recorded last, though it sorts first.
        seedPhone("acc-b", "acc-a")

        val result = upgrade().run(signedInUser = null)

        assertEquals(UpgradeResult.MovedTo("acc-a"), result)
        assertEquals(listOf("Dune", "Emma"), books(LibraryId.Account("acc-a")))
        assertFalse(fs.exists(dbPath(LibraryId.Account("acc-b"))))
        assertEquals("acc-a", devicePrefs.values["library.active"])
    }

    @Test
    fun interruptedUpgradeRetriesFromTheOriginal() = runTest(timeout = 60.seconds) {
        seedPhone("owner-1")
        // What a run killed partway leaves: a half-copied database and cover folder, and one ebook already moved.
        writeFile(dbPath(owner), "half a database")
        writeFile(files.coversDir(owner) / "partial.jpg", "half")
        fs.createDirectories(files.ebooksDir(owner))
        fs.atomicMove(files.ebooksDir(LibraryId.Local) / "1.epub", files.ebooksDir(owner) / "1.epub")

        val result = upgrade().run(signedInUser = null)

        assertEquals(UpgradeResult.MovedTo("owner-1"), result)
        assertEquals(listOf("Dune", "Emma"), books(owner))
        assertEquals(listOf("c1.jpg"), regularFiles(files.coversDir(owner)))
        assertEquals(listOf("1.epub", "2.pdf"), regularFiles(files.ebooksDir(owner)))
        assertEquals(emptyList<String>(), books(LibraryId.Local))
        assertEquals("owner-1", devicePrefs.values["library.active"])
    }

    @Test
    fun failedCheckKeepsOriginals() = runTest(timeout = 60.seconds) {
        seedPhone("owner-1")
        // The copy comes out short a book.
        val badCopy: (LibraryId) -> AppDatabase = { id ->
            openLibraryDatabase(context, id).also { db ->
                if (id == owner) runBlocking { db.bookDao().getAll().firstOrNull()?.let { db.bookDao().delete(it.id) } }
            }
        }

        val result = upgrade(badCopy).run(signedInUser = null)

        assertTrue(result is UpgradeResult.Failed)
        assertEquals(listOf("Dune", "Emma"), books(LibraryId.Local))
        assertTrue(withDb(LibraryId.Local) { it.syncStateDao().anyMerged() })
        assertEquals("jpeg", read(files.coversDir(LibraryId.Local) / "c1.jpg"))
        assertEquals(listOf("1.epub", "2.pdf"), regularFiles(files.ebooksDir(LibraryId.Local)))
        assertFalse(fs.exists(dbPath(owner)))
        assertFalse(fs.exists(files.coversDir(owner)))
        assertEquals(emptyList<String>(), regularFiles(files.ebooksDir(owner)))
        assertFalse(devicePrefs.values.containsKey("library.upgraded"))
        assertNotEquals("owner-1", devicePrefs.values["library.active"])

        // Retried at the next start.
        assertEquals(UpgradeResult.MovedTo("owner-1"), upgrade().run(signedInUser = null))
        assertEquals(listOf("Dune", "Emma"), books(owner))
    }

    @Test
    fun runsOnlyOnce() = runTest(timeout = 60.seconds) {
        seedPhone()
        assertEquals(UpgradeResult.StayedLocal, upgrade().run(signedInUser = null))
        // Signing in later merges the no-account library: that is not an old install to upgrade.
        withDb(LibraryId.Local) { it.syncStateDao().set("merged:owner-1", "1") }

        assertEquals(UpgradeResult.NothingToDo, upgrade().run(signedInUser = null))
        assertEquals(listOf("Dune", "Emma"), books(LibraryId.Local))
        assertFalse(fs.exists(dbPath(owner)))
    }

    @Test
    fun freshInstallOnlyMarksItDone() = runTest(timeout = 60.seconds) {
        assertEquals(UpgradeResult.NothingToDo, upgrade().run(signedInUser = null))

        assertTrue(devicePrefs.values.containsKey("library.upgraded"))
        assertFalse(fs.exists(dbPath(LibraryId.Local)))
    }

    @Test
    fun ebooksMoveIntoTheAccountsEbooksFolder() = runTest(timeout = 60.seconds) {
        seedPhone("owner-1")
        // Another account's ebooks, already in their own folder inside the no-account one.
        writeFile(files.ebooksDir(LibraryId.Account("acc-b")) / "9.epub", "theirs")

        assertEquals(UpgradeResult.MovedTo("owner-1"), upgrade().run(signedInUser = null))

        assertEquals(listOf("1.epub", "2.pdf"), regularFiles(files.ebooksDir(owner)))
        assertFalse(fs.exists(files.ebooksDir(owner) / "acc-b"))
        assertEquals("theirs", read(files.ebooksDir(LibraryId.Account("acc-b")) / "9.epub"))
        assertEquals(emptyList<String>(), regularFiles(files.ebooksDir(LibraryId.Local)))
        // Account ebooks stay out of the backed-up libraries folder.
        assertFalse(fs.exists(files.filesDir / "libraries" / "owner-1" / "ebooks"))
    }

    private fun device() = run {
        val http = lookupHttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }, "MyBooks test")
        DeviceServices(
            lookup = LookupChain(emptyList()),
            authorBooks = GoogleBooksAuthorSearch(http, apiKey = ""),
            subjects = SubjectSearch(http, apiKey = ""),
            social = null,
            releases = ReleasesRepository(ReleasesClient(http), FileSystem.SYSTEM, files.filesDir / "releases"),
            events = ReadingEvents(),
            preferences = devicePrefs,
            clock = { 0L },
        )
    }
}
