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

    /**
     * It failed (and was logged). Before the copy was verified: everything is as it was, the no-account library opens the
     * originals, and the next start tries again. After: the copy is the account's library, and the next start finishes
     * clearing the originals.
     */
    data class Failed(val cause: Throwable) : UpgradeResult
}

/**
 * Upgrades an install from before libraries were separate, once, before anything opens `mybooks.db`.
 *
 * If today's `mybooks.db` joined an account's cloud library (a `merged:<owner>` key), it becomes that account's library:
 * the database and covers are copied, the ebooks moved (renamed) into the account's folders, and the copy checked (same
 * live book count). Of several owners, the signed-in one wins, else the one recorded last. The library's two preferences
 * (pinned tags and "Not interested") move from the device settings into its own database.
 *
 * Until the copy is verified, any failure puts everything back as it was, and a run killed partway starts again from the
 * originals. The copy becomes the library in one step: `mybooks.db` is renamed aside (`mybooks.db.moved-to-<owner>`),
 * which the file system does whole or not at all. From then on the copy is never deleted or copied again: while the
 * renamed original is there, a start only finishes clearing the originals (and makes the new, empty no-account library),
 * and the renamed original goes last.
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
        try {
            val verified = verifiedCopy()
            when {
                verified != null -> {
                    finish(verified, setActive = false)
                    UpgradeResult.MovedTo(verified.userId)
                }
                deviceSettings.get(UPGRADED) != null -> UpgradeResult.NothingToDo
                else -> upgrade(signedInUser).also { deviceSettings.set(UPGRADED, "1") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logLibraryError("Library upgrade failed; it is tried again at the next start", e)
            UpgradeResult.Failed(e)
        }
    }

    private suspend fun upgrade(signedInUser: String?): UpgradeResult {
        val original = databaseFile(LibraryId.Local)
        if (!fs.exists(original)) {
            // No library yet: stray journal files must not meet the new database.
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
        val valid = owners.mapNotNull(::accountOrNull)
        return valid.firstOrNull { it.userId == signedInUser } ?: valid.lastOrNull()
    }

    /** Copies today's library to [owner]'s, checks it holds [books] live books, makes it the library, then clears the originals. */
    private suspend fun moveTo(owner: LibraryId.Account, books: Int) {
        val original = databaseFile(LibraryId.Local)
        val copy = databaseFile(owner)
        // Every ebook rename (from, to), to undo them.
        val renames = mutableListOf<Pair<Path, Path>>()
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
            moveEbooks(owner, renames)
            withDatabase(owner) { db ->
                val copied = db.bookDao().getAll().size
                check(copied == books) { "The copied library has $copied books, the original $books" }
                for ((key, value) in devicePrefs()) storeLibraryPreference(db.syncStateDao(), key, value)
            }
            // The copy is verified; this one rename makes it the library (it happens whole or not at all).
            fs.atomicMove(original, aside(owner))
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                // Back as it was: the ebooks to their folder, the partial copy gone, the no-account library shown.
                runCatching { for ((from, to) in renames.asReversed()) fs.atomicMove(to, from) }
                runCatching { deleteDatabaseFiles(copy) }
                runCatching { fs.deleteRecursively(files.coversDir(owner)) }
                if (deviceSettings.get(ACTIVE) == owner.key) deviceSettings.set(ACTIVE, LibraryId.Local.key)
            }
            throw e
        }
        finish(owner, setActive = true)
    }

    /**
     * The copy is [owner]'s library (the original is renamed aside): clears what is left of the originals, makes the
     * new, empty no-account library, and marks the upgrade done; the renamed original goes last, so a run stopped
     * anywhere here (killed, or a delete failing) is finished by the next start. Repeating it changes nothing that was
     * made since: the copy and anything the no-account library holds now are left alone, and the device's preferences
     * aren't copied again (the copy has them).
     */
    private suspend fun finish(owner: LibraryId.Account, setActive: Boolean) {
        // A later start leaves the library a session may have chosen since.
        if (setActive || deviceSettings.get(ACTIVE) == null) deviceSettings.set(ACTIVE, owner.key)
        val local = databaseFile(LibraryId.Local)
        // The original's journal files, only while no new no-account library sits beside them.
        if (!fs.exists(local)) deleteDatabaseFiles(local)
        // The original covers are the ones copied: a cover the no-account library got since has another (random) name.
        val localCovers = files.coversDir(LibraryId.Local)
        for (cover in regularFiles(files.coversDir(owner))) fs.delete(localCovers / cover.name, mustExist = false)
        if (!fs.exists(local)) withDatabase(LibraryId.Local) { it.bookDao().getAll() }
        deviceSettings.set(UPGRADED, "1")
        fs.delete(aside(owner), mustExist = false)
    }

    /** Where the original `mybooks.db` is renamed once its copy is [owner]'s verified library. */
    private fun aside(owner: LibraryId.Account): Path = withSuffix(databaseFile(LibraryId.Local), ASIDE + owner.userId)

    /** The account whose verified copy is the library while the original still sits renamed aside; null if none. */
    private fun verifiedCopy(): LibraryId.Account? {
        val local = databaseFile(LibraryId.Local)
        val prefix = local.name + ASIDE
        val dir = local.parent ?: return null
        return fs.listOrNull(dir).orEmpty().firstNotNullOfOrNull { path ->
            if (path.name.startsWith(prefix)) accountOrNull(path.name.removePrefix(prefix)) else null
        }
    }

    private fun accountOrNull(key: String): LibraryId.Account? = try {
        LibraryId.Account(key)
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * What a run killed partway left in [owner]'s place: the partial copy goes, and ebooks it had already moved go back
     * (they are the originals; one the no-account folder holds again stays, and is kept aside by [moveEbooks]).
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
     * each rename in [renames]. Folders in it are other accounts' and stay. Nothing is overwritten: a file already there
     * under the same name is first renamed to a free name beside it, so the library's own file is where its record
     * expects it and both are kept.
     */
    private fun moveEbooks(owner: LibraryId.Account, renames: MutableList<Pair<Path, Path>>) {
        val ebooks = regularFiles(files.ebooksDir(LibraryId.Local))
        if (ebooks.isEmpty()) return
        val to = files.ebooksDir(owner)
        fs.createDirectories(to)
        for (file in ebooks) {
            val target = to / file.name
            if (fs.exists(target)) {
                val kept = generateSequence(1) { it + 1 }.map { to / "${file.name}.kept$it" }.first { !fs.exists(it) }
                fs.atomicMove(target, kept)
                renames += target to kept
            }
            fs.atomicMove(file, target)
            renames += file to target
        }
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

        /** `mybooks.db` + this + the owner: the original, renamed aside once its copy is verified. */
        const val ASIDE = ".moved-to-"

        /** The main file first: once it's gone, the rest can't be mistaken for a library. */
        val DATABASE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")
    }
}
