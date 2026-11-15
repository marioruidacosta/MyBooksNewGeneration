package com.mcosta.mybooks.library

import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.data.db.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okio.Path

/** What [LibraryUpgrade.run] did. */
sealed interface UpgradeResult {
    /** Already upgraded, or a new install with no library yet. */
    data object NothingToDo : UpgradeResult

    /** Today's library had joined [account]'s cloud library: it is now [account]'s library, and the no-account one is new. */
    data class MovedTo(val account: String) : UpgradeResult

    /** Today's library never joined an account: it stays the no-account library. */
    data object StayedLocal : UpgradeResult

    /** It failed: the no-account library opens what is left (the originals, unless the copy was already verified), and the next start tries again. */
    data class Failed(val cause: Throwable) : UpgradeResult
}

/**
 * Upgrades an install from before libraries were separate, once, before anything opens `mybooks.db`.
 *
 * If today's `mybooks.db` joined an account's cloud library (a `merged:<owner>` key), it becomes that account's library:
 * the database and covers are copied, the ebooks moved (renamed) into the account's folders, and the copy checked (same
 * live book count) before the originals go and an empty no-account library takes their place. Of several owners, the
 * signed-in one wins, else the one recorded last. The library's two preferences (pinned tags and "Not interested") move
 * from the device settings into its own database. Until the copy is verified, any failure puts everything back as it
 * was; a run killed partway starts again from the originals at the next start.
 *
 * [databaseFile] is where a library's database file is (its `-wal`/`-shm` next to it); [openDatabase] opens it.
 */
class LibraryUpgrade(
    private val deviceSettings: Preferences,
    private val files: LibraryFiles,
    private val databaseFile: (LibraryId) -> Path,
    private val openDatabase: (LibraryId) -> AppDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val fs = files.fileSystem

    /** Upgrades, unless that's done; [signedInUser] is the stored session's user, if it could be read. Never throws. */
    suspend fun run(signedInUser: String?): UpgradeResult = withContext(io) {
        if (deviceSettings.get(UPGRADED) != null) return@withContext UpgradeResult.NothingToDo
        try {
            upgrade(signedInUser).also { deviceSettings.set(UPGRADED, "1") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpgradeResult.Failed(e)
        }
    }

    private suspend fun upgrade(signedInUser: String?): UpgradeResult {
        val original = databaseFile(LibraryId.Local)
        if (!fs.exists(original)) {
            // No library yet (or the original went just before a killed run was marked done): stray journal files
            // must not meet the new database.
            deleteDatabaseFiles(original)
            storePrefs(LibraryId.Local)
            return UpgradeResult.NothingToDo
        }
        val (owners, books) = withDatabase(LibraryId.Local) { db ->
            db.syncStateDao().mergedOwners() to db.bookDao().getAll().size
        }
        val owner = ownerOf(owners, signedInUser)
        if (owner == null) {
            storePrefs(LibraryId.Local)
            return UpgradeResult.StayedLocal
        }
        moveTo(owner, books)
        return UpgradeResult.MovedTo(owner.userId)
    }

    /** The signed-in user if among [owners], else the one recorded last; null if none (ids that can't name a library are skipped). */
    private fun ownerOf(owners: List<String>, signedInUser: String?): LibraryId.Account? {
        val valid = owners.mapNotNull { key ->
            try {
                LibraryId.Account(key)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        return valid.firstOrNull { it.userId == signedInUser } ?: valid.lastOrNull()
    }

    /** Copies today's library to [owner]'s, checks it holds [books] live books, then lets the originals go. */
    private suspend fun moveTo(owner: LibraryId.Account, books: Int) {
        val original = databaseFile(LibraryId.Local)
        val copy = databaseFile(owner)
        val moved = mutableListOf<Path>()
        try {
            removeLeftovers(owner)
            for (suffix in DATABASE_SUFFIXES) {
                val from = withSuffix(original, suffix)
                if (fs.exists(from)) {
                    copy.parent?.let(fs::createDirectories)
                    fs.copy(from, withSuffix(copy, suffix))
                }
            }
            copyFolder(files.coversDir(LibraryId.Local), files.coversDir(owner))
            moveEbooks(owner, moved)
            withDatabase(owner) { db ->
                val copied = db.bookDao().getAll().size
                check(copied == books) { "The copied library has $copied books, the original $books" }
                for ((key, value) in devicePrefs()) storeLibraryPreference(db.syncStateDao(), key, value)
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                // Back as it was: the ebooks to their folder, the partial copy gone, the no-account library shown.
                runCatching { moveBack(moved) }
                runCatching { deleteDatabaseFiles(copy) }
                runCatching { fs.deleteRecursively(files.coversDir(owner)) }
                if (deviceSettings.get(ACTIVE) == owner.key) deviceSettings.set(ACTIVE, LibraryId.Local.key)
            }
            throw e
        }
        // The copy is verified. From here nothing is put back: the next start opens the copy.
        deviceSettings.set(ACTIVE, owner.key)
        // The database first: if this is killed between the two, the next run finds no original and only stray covers.
        deleteDatabaseFiles(original)
        fs.deleteRecursively(files.coversDir(LibraryId.Local))
        // The new, empty no-account library.
        withDatabase(LibraryId.Local) { it.bookDao().getAll() }
    }

    /**
     * What a run killed partway left in [owner]'s place: the partial copy goes, and ebooks it had already moved go back
     * (they are the originals; one the no-account folder holds again is left to be replaced by it).
     */
    private fun removeLeftovers(owner: LibraryId.Account) {
        deleteDatabaseFiles(databaseFile(owner))
        fs.deleteRecursively(files.coversDir(owner))
        val local = files.ebooksDir(LibraryId.Local)
        for (file in regularFiles(files.ebooksDir(owner))) {
            val back = local / file.name
            if (!fs.exists(back)) fs.atomicMove(file, back)
        }
    }

    /**
     * Renames the no-account library's ebook files into [owner]'s folder (inside it, so on the same file system), noting
     * each in [moved]. Folders in it are other accounts' and stay.
     */
    private fun moveEbooks(owner: LibraryId.Account, moved: MutableList<Path>) {
        val ebooks = regularFiles(files.ebooksDir(LibraryId.Local))
        if (ebooks.isEmpty()) return
        val to = files.ebooksDir(owner)
        fs.createDirectories(to)
        for (file in ebooks) {
            val target = to / file.name
            fs.atomicMove(file, target)
            moved += target
        }
    }

    private fun moveBack(moved: List<Path>) {
        val local = files.ebooksDir(LibraryId.Local)
        for (file in moved.asReversed()) fs.atomicMove(file, local / file.name)
    }

    private fun regularFiles(dir: Path): List<Path> =
        fs.listOrNull(dir).orEmpty().filter { fs.metadataOrNull(it)?.isRegularFile == true }

    private fun copyFolder(from: Path, to: Path) {
        if (!fs.exists(from)) return
        fs.createDirectories(to)
        for (path in fs.listRecursively(from)) {
            val target = to / path.relativeTo(from)
            if (fs.metadata(path).isDirectory) fs.createDirectories(target) else fs.copy(path, target)
        }
    }

    private fun deleteDatabaseFiles(db: Path) {
        for (suffix in DATABASE_SUFFIXES) fs.delete(withSuffix(db, suffix), mustExist = false)
    }

    private fun withSuffix(db: Path, suffix: String): Path = db.parent?.let { it / (db.name + suffix) } ?: db

    /** The library's preferences still in the device settings (where they were kept before libraries were separate). */
    private fun devicePrefs(): List<Pair<String, String>> =
        PER_LIBRARY_PREF_KEYS.mapNotNull { key -> deviceSettings.get(key)?.let { key to it } }

    private suspend fun storePrefs(id: LibraryId) {
        val prefs = devicePrefs()
        if (prefs.isEmpty()) return
        withDatabase(id) { db -> for ((key, value) in prefs) storeLibraryPreference(db.syncStateDao(), key, value) }
    }

    private suspend fun <T> withDatabase(id: LibraryId, block: suspend (AppDatabase) -> T): T {
        val db = openDatabase(id)
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    private companion object {
        /** Set once the upgrade is done (either way). */
        const val UPGRADED = "library.upgraded"

        /** [Libraries]' active library. */
        const val ACTIVE = "library.active"

        /** The main file first: once it's gone, the rest can't be mistaken for a library. */
        val DATABASE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")
    }
}
