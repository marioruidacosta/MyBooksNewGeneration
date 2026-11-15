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
import okio.ForwardingFileSystem
import okio.IOException
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
import org.robolectric.shadows.ShadowLog
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

        /** The next write of this key throws (as if the process died there). */
        var failOn: String? = null

        override fun get(key: String) = values[key]
        override fun set(key: String, value: String) {
            if (key == failOn) {
                failOn = null
                throw IllegalStateException("injected: $key")
            }
            values[key] = value
        }
    }

    /** The real file system, but deleting or moving the paths given fails. */
    private class FaultyFileSystem(
        private val failDelete: (Path) -> Boolean = { false },
        private val failMove: (Path) -> Boolean = { false },
    ) : ForwardingFileSystem(FileSystem.SYSTEM) {
        override fun delete(path: Path, mustExist: Boolean) {
            if (failDelete(path)) throw IOException("injected: delete $path")
            super.delete(path, mustExist)
        }

        override fun atomicMove(source: Path, target: Path) {
            if (failMove(source)) throw IOException("injected: move $source")
            super.atomicMove(source, target)
        }
    }

    private val devicePrefs = MemoryPreferences()

    private val owner = LibraryId.Account("owner-1")

    private fun dbPath(id: LibraryId): Path = libraryDatabaseFile(context, id).toOkioPath()

    private fun upgrade(
        open: (Path) -> AppDatabase = { openDatabaseFile(context, it.toFile()) },
        fileSystem: FileSystem = fs,
    ) = LibraryUpgrade(devicePrefs, LibraryFiles(fileSystem, files.filesDir), ::dbPath, open)

    private fun localDbFile(suffix: String) = dbPath(LibraryId.Local).let { it.parent!! / (it.name + suffix) }

    /** The owner's database file with [suffix] (`.upgrading` is the copy's temporary name). */
    private fun ownerDbFile(suffix: String) = dbPath(owner).let { it.parent!! / (it.name + suffix) }

    /** Where the covers are copied before the copy is checked. */
    private val tempCovers get() = files.coversDir(owner).parent!! / "covers.upgrading"

    /**
     * Files next to `mybooks.db` that only the upgrade makes (the original set aside); none once it's done. SQLite's own
     * files (journals, Robolectric's `.lck`) aren't counted.
     */
    private fun upgradeLeftovers() = fs.listOrNull(dbPath(LibraryId.Local).parent!!).orEmpty().map { it.name }
        .filter { it.startsWith("mybooks.db") && !it.endsWith(".lck") }
        .filter { it !in setOf("mybooks.db", "mybooks.db-wal", "mybooks.db-shm", "mybooks.db-journal") }

    @After
    fun tearDown() {
        scope.cancel()
        for (id in listOf(LibraryId.Local, owner, LibraryId.Account("acc-a"), LibraryId.Account("acc-b"))) {
            val db = dbPath(id)
            for (suffix in listOf("", "-wal", "-shm", "-journal")) fs.delete(db.parent!! / (db.name + suffix))
        }
        for (name in upgradeLeftovers()) fs.delete(dbPath(LibraryId.Local).parent!! / name)
        fs.deleteRecursively(dbPath(owner).parent!!)
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
        assertEquals(emptyList<String>(), upgradeLeftovers())
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
        writeFile(ownerDbFile(".upgrading"), "half a database")
        writeFile(tempCovers / "partial.jpg", "half")
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
    fun partialTemporaryCopyIsRemovedOnRetry() = runTest(timeout = 60.seconds) {
        seedPhone("owner-1")
        // Killed while copying: the database and its journal, and the covers, half there under their temporary names.
        writeFile(ownerDbFile(".upgrading"), "half a database")
        writeFile(ownerDbFile(".upgrading-wal"), "half a journal")
        writeFile(tempCovers / "partial.jpg", "half")
        // Deletes are recorded: the account's own database name is never deleted.
        val deleted = mutableListOf<Path>()
        val recording = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun delete(path: Path, mustExist: Boolean) {
                deleted += path
                super.delete(path, mustExist)
            }
        }

        assertEquals(UpgradeResult.MovedTo("owner-1"), upgrade(fileSystem = recording).run(signedInUser = null))

        assertFinished()
        assertFalse(fs.exists(ownerDbFile(".upgrading")))
        assertFalse(fs.exists(ownerDbFile(".upgrading-wal")))
        assertFalse(fs.exists(tempCovers))
        val accountDb = listOf("", "-wal", "-shm", "-journal").map { ownerDbFile(it) }
        assertTrue(deleted.toString(), deleted.none { it in accountDb })
    }

    @Test
    fun existingAccountLibraryStopsTheUpgrade() = runTest(timeout = 60.seconds) {
        seedPhone("owner-1")
        // The account already has a library on this phone, with a book and a cover of its own.
        withDb(owner) { it.bookDao().insert(Book(title = "Persuasion").toEntity()) }
        writeFile(files.coversDir(owner) / "theirs.jpg", "theirs")

        val result = upgrade().run(signedInUser = "owner-1")

        assertTrue(result.toString(), result is UpgradeResult.Failed)
        // Its owner waits for the retry: no library is opened for it meanwhile.
        assertEquals(setOf(owner), (result as UpgradeResult.Failed).pending)
        assertTrue(ShadowLog.getLogsForTag("MyBooks").any { it.msg.contains("upgrade", ignoreCase = true) })
        // The account's library is untouched.
        assertEquals(listOf("Persuasion"), books(owner))
        assertEquals(listOf("theirs.jpg"), regularFiles(files.coversDir(owner)))
        assertEquals(emptyList<String>(), regularFiles(files.ebooksDir(owner)))
        // So is the original, which the next start tries again.
        assertEquals(listOf("Dune", "Emma"), books(LibraryId.Local))
        assertTrue(withDb(LibraryId.Local) { it.syncStateDao().anyMerged() })
        assertEquals("jpeg", read(files.coversDir(LibraryId.Local) / "c1.jpg"))
        assertEquals(listOf("1.epub", "2.pdf"), regularFiles(files.ebooksDir(LibraryId.Local)))
        assertEquals(emptyList<String>(), upgradeLeftovers())
        assertFalse(devicePrefs.values.containsKey("library.upgraded"))
        assertNotEquals("owner-1", devicePrefs.values["library.active"])
        assertFalse(fs.exists(ownerDbFile(".upgrading")))
        assertFalse(fs.exists(tempCovers))
    }

    @Test
    fun killedWhilePuttingTheCopyInPlaceRetriesFromTheOriginal() = runTest(timeout = 60.seconds) {
        seedPhone("owner-1")
        // Killed after the checked copy was put in place, before the original was renamed aside: the marker says those
        // files are the upgrade's own.
        writeFile(dbPath(owner), "a placed copy")
        writeFile(files.coversDir(owner) / "c1.jpg", "jpeg")
        writeFile(ownerDbFile(".placing"), "")

        assertEquals(UpgradeResult.MovedTo("owner-1"), upgrade().run(signedInUser = null))

        assertFinished()
        assertFalse(fs.exists(ownerDbFile(".placing")))
    }

    @Test
    fun failedCheckKeepsOriginals() = runTest(timeout = 60.seconds) {
        seedPhone("owner-1")
        // The copy comes out short a book.
        val badCopy: (Path) -> AppDatabase = { path ->
            openDatabaseFile(context, path.toFile()).also { db ->
                if (path.parent == dbPath(owner).parent) runBlocking { db.bookDao().getAll().firstOrNull()?.let { db.bookDao().delete(it.id) } }
            }
        }

        val result = upgrade(badCopy).run(signedInUser = null)

        assertTrue(result is UpgradeResult.Failed)
        assertEquals(setOf(owner), (result as UpgradeResult.Failed).pending)
        assertTrue(ShadowLog.getLogsForTag("MyBooks").any { it.msg.contains("upgrade", ignoreCase = true) })
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

    /** After the upgrade finished with [owner]'s library: the copy kept, the no-account library empty, nothing left over. */
    private suspend fun assertFinished(titles: List<String> = listOf("Dune", "Emma")) {
        assertEquals(titles, books(owner))
        assertEquals("Poetry", libraryPref(owner, "home.pinnedTags"))
        assertEquals(listOf("1.epub", "2.pdf"), regularFiles(files.ebooksDir(owner)))
        assertEquals(listOf("c1.jpg"), regularFiles(files.coversDir(owner)))
        assertEquals(emptyList<String>(), regularFiles(files.coversDir(LibraryId.Local)))
        assertEquals(emptyList<String>(), books(LibraryId.Local))
        assertNull(libraryPref(LibraryId.Local, "home.pinnedTags"))
        assertNull(libraryPref(LibraryId.Local, "suggestions.hidden"))
        assertEquals(emptyList<String>(), upgradeLeftovers())
        // Nothing temporary is left beside the account's database or covers (Robolectric's `.lck` files aside).
        val beside = fs.list(dbPath(owner).parent!!).map { it.name }.filter { !it.endsWith(".lck") }
        assertTrue(beside.toString(), beside.none { ".upgrading" in it || it.endsWith(".placing") })
        assertFalse(fs.exists(tempCovers))
        assertEquals("owner-1", devicePrefs.values["library.active"])
        assertTrue(devicePrefs.values.containsKey("library.upgraded"))
    }

    @Test
    fun crashAfterTheOriginalWentFinishesTheCleanup() = runTest(timeout = 60.seconds) {
        seedPhone("owner-1")
        // Killed once the original database was out of the way, before its journal files and covers went.
        val killed = FaultyFileSystem(failDelete = { it.name == "mybooks.db-wal" })
        assertTrue(upgrade(fileSystem = killed).run(signedInUser = null) is UpgradeResult.Failed)
        writeFile(localDbFile("-wal"), "stale wal")
        writeFile(localDbFile("-shm"), "stale shm")
        assertEquals("jpeg", read(files.coversDir(LibraryId.Local) / "c1.jpg"))

        assertEquals(UpgradeResult.MovedTo("owner-1"), upgrade().run(signedInUser = null))

        assertFalse(fs.exists(localDbFile("-wal")) && read(localDbFile("-wal")) == "stale wal")
        assertFalse(fs.exists(localDbFile("-shm")) && read(localDbFile("-shm")) == "stale shm")
        assertFinished()
    }

    @Test
    fun crashAfterTheNewLocalWasCreatedFinishesTheCleanup() = runTest(timeout = 60.seconds) {
        seedPhone("owner-1")
        // Killed after the new, empty no-account library was made, before the upgrade was marked done.
        devicePrefs.failOn = "library.upgraded"
        assertTrue(upgrade().run(signedInUser = null) is UpgradeResult.Failed)
        assertTrue(fs.exists(dbPath(LibraryId.Local)))
        assertFalse(devicePrefs.values.containsKey("library.upgraded"))

        assertEquals(UpgradeResult.MovedTo("owner-1"), upgrade().run(signedInUser = null))

        assertFinished()
    }

    @Test
    fun failedDeleteOfTheOriginalKeepsTheCopyInUse() = runTest(timeout = 60.seconds) {
        seedPhone("owner-1")
        // The original database can't be deleted, and the app goes on running.
        val stuck = FaultyFileSystem(failDelete = { it == dbPath(LibraryId.Local) || it.name.startsWith("mybooks.db.") })
        val failed = upgrade(fileSystem = stuck).run(signedInUser = null)
        // The copy is the library already: its owner doesn't wait.
        assertEquals(emptySet<LibraryId.Account>(), (failed as UpgradeResult.Failed).pending)
        assertEquals("owner-1", devicePrefs.values["library.active"])
        // The session that follows works on the copy.
        withDb(owner) { it.bookDao().insert(Book(title = "Persuasion").toEntity()) }

        assertEquals(UpgradeResult.MovedTo("owner-1"), upgrade().run(signedInUser = null))

        // Never copied again from the original.
        assertFinished(titles = listOf("Dune", "Emma", "Persuasion"))
    }

    @Test
    fun failureSettingTheOriginalAsidePutsEverythingBack() = runTest(timeout = 60.seconds) {
        seedPhone("owner-1")
        val stuck = FaultyFileSystem(failMove = { it == dbPath(LibraryId.Local) })

        assertTrue(upgrade(fileSystem = stuck).run(signedInUser = null) is UpgradeResult.Failed)

        assertEquals(listOf("Dune", "Emma"), books(LibraryId.Local))
        assertTrue(withDb(LibraryId.Local) { it.syncStateDao().anyMerged() })
        assertEquals("jpeg", read(files.coversDir(LibraryId.Local) / "c1.jpg"))
        assertEquals(listOf("1.epub", "2.pdf"), regularFiles(files.ebooksDir(LibraryId.Local)))
        assertFalse(fs.exists(dbPath(owner)))
        assertNotEquals("owner-1", devicePrefs.values["library.active"])
        assertFalse(devicePrefs.values.containsKey("library.upgraded"))

        assertEquals(UpgradeResult.MovedTo("owner-1"), upgrade().run(signedInUser = null))
        assertFinished()
    }

    @Test
    fun ebookRenameNeverOverwrites() = runTest(timeout = 60.seconds) {
        seedPhone("owner-1")
        // A file already in the account's folder under the same name as one of the phone's.
        writeFile(files.ebooksDir(owner) / "1.epub", "older")

        assertEquals(UpgradeResult.MovedTo("owner-1"), upgrade().run(signedInUser = null))

        // The library's own file is where its record expects it; the other is kept beside it.
        assertEquals("epub-1", read(files.ebooksDir(owner) / "1.epub"))
        val kept = regularFiles(files.ebooksDir(owner)).map { read(files.ebooksDir(owner) / it) }
        assertEquals(listOf("epub-1", "older", "pdf-2"), kept.sorted())
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
