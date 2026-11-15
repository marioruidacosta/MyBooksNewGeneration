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
     * originals, and the next start tries again; [pending] are the accounts the original belongs to (its `merged:`
     * owners, when they could be read), which get no library of their own until then. After: the copy is the account's
     * library, the next start finishes clearing the originals, and [pending] is empty.
     */
    data class Failed(val cause: Throwable, val pending: Set<LibraryId.Account> = emptySet()) : UpgradeResult
}

/**
 * Upgrades an install from before libraries were separate, once, before anything opens `mybooks.db`.
 *
 * If today's `mybooks.db` joined an account's cloud library (a `merged:<owner>` key), it becomes that account's library:
 * the database and covers are copied, the ebooks moved (renamed) into the account's folders, and the copy checked (same
 * live book count). Of several owners, the signed-in one wins, else the one recorded last. The library's two preferences
 * (pinned tags and "Not interested") move from the device settings into its own database.
 *
 * The database and covers are copied under temporary names (`libraries/<owner>.db.upgrading`, `covers.upgrading`), and
 * renamed into place only once the copy is verified. If the account already has a library on this phone, nothing is
 * deleted or replaced: the upgrade stops (failed), the original stays as it is, and the next start tries again.
 *
 * Until the copy is verified, any failure puts everything back as it was, and a run killed partway starts again from the
 * originals: it deletes only its own temporary files (and a copy it was putting in place, which a marker file,
 * `<owner>.db.placing`, says is its own). The copy becomes the library in one step: `mybooks.db` is renamed aside
 * (`mybooks.db.moved-to-<owner>`), which the file system does whole or not at all. From then on the copy is never
 * deleted or copied again: while the renamed original is there, a start only finishes clearing the originals (and makes
 * the new, empty no-account library), and the renamed original goes last.
 *
 * [databaseFile] is where a library's database file is (its `-wal`/`-shm` next to it); [openDatabase] opens a database
 * file.
 */
class LibraryUpgrade(
    private val deviceSettings: Preferences,
    private val files: LibraryFiles,
    private val databaseFile: (LibraryId) -> Path,
    private val openDatabase: (Path) -> AppDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val fs = files.fileSystem

    /** Upgrades, unless that's done; [signedInUser] is the stored session's user, if it could be read. Never throws. */
    suspend fun run(signedInUser: String?): UpgradeResult = withContext(io) {
        // The original's owners, once read: if the upgrade fails before its copy is the library, they wait for the retry.
        var owners = emptyList<LibraryId.Account>()
        try {
            val verified = verifiedCopy()
            when {
                verified != null -> {
                    finish(verified, setActive = false)
                    UpgradeResult.MovedTo(verified.userId)
                }
                deviceSettings.get(UPGRADED) != null -> UpgradeResult.NothingToDo
                else -> upgrade(signedInUser) { owners = it }.also { deviceSettings.set(UPGRADED, "1") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logLibraryError("Library upgrade failed; it is tried again at the next start", e)
            // If it can't be told whether the original was set aside, its owners wait: that loses nothing either way.
            val committed = try {
                verifiedCopy() != null
            } catch (_: Exception) {
                false
            }
            UpgradeResult.Failed(e, if (committed) emptySet() else owners.toSet())
        }
    }

    /** [onOwners] gets the original's (valid) `merged:` owners as soon as they're read. */
    private suspend fun upgrade(signedInUser: String?, onOwners: (List<LibraryId.Account>) -> Unit): UpgradeResult {
        val original = databaseFile(LibraryId.Local)
        if (!fs.exists(original)) {
            // No library yet: stray journal files must not meet the new database.
            deleteDatabaseFiles(original)
            storePrefs(LibraryId.Local)
            return UpgradeResult.NothingToDo
        }
        val (merged, books) = withDatabase(LibraryId.Local) { db ->
            db.syncStateDao().mergedOwners() to db.bookDao().getAll().size
        }
        // Ids that can't name a library are skipped.
        val owners = merged.mapNotNull(::accountOrNull)
        onOwners(owners)
        val owner = ownerOf(owners, signedInUser)
        if (owner == null) {
            storePrefs(LibraryId.Local)
            return UpgradeResult.StayedLocal
        }
        moveTo(owner, books)
        return UpgradeResult.MovedTo(owner.userId)
    }

    /** The signed-in user if among [owners], else the one recorded last; null if none. */
    private fun ownerOf(owners: List<LibraryId.Account>, signedInUser: String?): LibraryId.Account? =
        owners.firstOrNull { it.userId == signedInUser } ?: owners.lastOrNull()

    /**
     * Copies today's library to [owner]'s (under temporary names), checks it holds [books] live books, puts it in place,
     * makes it the library, then clears the originals. Stops (throws) before touching anything if [owner] already has a
     * library here.
     */
    private suspend fun moveTo(owner: LibraryId.Account, books: Int) {
        val original = databaseFile(LibraryId.Local)
        val copy = databaseFile(owner)
        val tempCopy = upgrading(copy)
        val covers = files.coversDir(owner)
        val tempCovers = upgrading(covers)
        removeLeftovers(owner)
        // Every ebook rename (from, to), to undo them.
        val renames = mutableListOf<Pair<Path, Path>>()
        // Set once the verified copy goes into place: from then [owner]'s own names may hold its files (as the marker says).
        var placing = false
        try {
            for (suffix in DATABASE_SUFFIXES) {
                val from = withSuffix(original, suffix)
                if (fs.exists(from)) {
                    tempCopy.parent?.let(fs::createDirectories)
                    fs.copy(from, withSuffix(tempCopy, suffix))
                }
            }
            copyFolder(files.coversDir(LibraryId.Local), tempCovers)
            moveEbooks(owner, renames)
            withDatabase(tempCopy) { db ->
                val copied = db.bookDao().getAll().size
                check(copied == books) { "The copied library has $copied books, the original $books" }
                for ((key, value) in devicePrefs()) storeLibraryPreference(db.syncStateDao(), key, value)
            }
            // Verified: into place, the marker first and the main database file last (journals alone are no library).
            placing = true
            fs.write(placingMarker(owner)) {}
            if (fs.exists(tempCovers)) fs.atomicMove(tempCovers, covers)
            for (suffix in DATABASE_SUFFIXES.asReversed()) {
                val from = withSuffix(tempCopy, suffix)
                if (fs.exists(from)) fs.atomicMove(from, withSuffix(copy, suffix))
            }
            // This one rename makes it the library (it happens whole or not at all).
            fs.atomicMove(original, aside(owner))
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                // Set aside after all (or that can't be told): nothing is undone, and the next start finishes or retries.
                val committed = runCatching { fs.exists(aside(owner)) }.getOrDefault(true)
                if (!committed) {
                    // Back as it was: the ebooks to their folder, the copy gone, the no-account library shown.
                    runCatching { for ((from, to) in renames.asReversed()) fs.atomicMove(to, from) }
                    runCatching { deleteDatabaseFiles(tempCopy) }
                    runCatching { fs.deleteRecursively(tempCovers) }
                    if (placing) {
                        // The marker goes last, once what it vouches for is gone.
                        runCatching {
                            deleteDatabaseFiles(copy)
                            fs.deleteRecursively(covers)
                            fs.delete(placingMarker(owner), mustExist = false)
                        }
                    }
                    if (deviceSettings.get(ACTIVE) == owner.key) deviceSettings.set(ACTIVE, LibraryId.Local.key)
                }
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
        // The copy is in place for good.
        fs.delete(placingMarker(owner), mustExist = false)
        deviceSettings.set(UPGRADED, "1")
        fs.delete(aside(owner), mustExist = false)
    }

    /** [path]'s temporary name while it is copied (a database's journals beside it, as SQLite names them). */
    private fun upgrading(path: Path): Path = withSuffix(path, UPGRADING)

    /** There while the verified copy is put in place for [owner]: the files under [owner]'s names are the upgrade's. */
    private fun placingMarker(owner: LibraryId.Account): Path = withSuffix(databaseFile(owner), PLACING)

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
     * What a run killed partway left for [owner]: its temporary copy (database and covers) goes, so does a copy it was
     * putting in place (only while its marker is there), and ebooks it had already moved go back (they are the
     * originals; one the no-account folder holds again stays, and is kept aside by [moveEbooks]). Anything else under
     * [owner]'s own names is a library the upgrade didn't make: then nothing is deleted or moved, and it throws.
     */
    private fun removeLeftovers(owner: LibraryId.Account) {
        val copy = databaseFile(owner)
        val covers = files.coversDir(owner)
        val marker = placingMarker(owner)
        if (fs.exists(marker)) {
            // Killed while putting its verified copy in place, before the original was set aside: these are its files.
            deleteDatabaseFiles(copy)
            fs.deleteRecursively(covers)
            fs.delete(marker)
        }
        val existing = (DATABASE_SUFFIXES.map { withSuffix(copy, it) } + covers).filter(fs::exists)
        check(existing.isEmpty()) {
            "${owner.userId} already has a library on this phone (${existing.joinToString { it.name }}): it is kept, " +
                "and the upgrade stops"
        }
        deleteDatabaseFiles(upgrading(copy))
        fs.deleteRecursively(upgrading(covers))
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

    private suspend fun <T> withDatabase(id: LibraryId, block: suspend (AppDatabase) -> T): T = withDatabase(databaseFile(id), block)

    private suspend fun <T> withDatabase(file: Path, block: suspend (AppDatabase) -> T): T {
        val db = openDatabase(file)
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

        /** A copy's (and its covers folder's) name + this, until the copy is verified. */
        const val UPGRADING = ".upgrading"

        /** `libraries/<owner>.db` + this: the marker while the verified copy is put in place. */
        const val PLACING = ".placing"

        /** The main file first: once it's gone, the rest can't be mistaken for a library. */
        val DATABASE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")
    }
}
