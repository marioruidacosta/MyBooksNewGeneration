package com.mcosta.mybooks.library

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.mcosta.mybooks.data.db.AppDatabase
import com.mcosta.mybooks.data.db.createDatabase
import okio.FileSystem
import okio.Path.Companion.toPath

/** A library's database file (its -wal/-shm sit next to it). */
fun libraryDatabasePath(documents: String, id: LibraryId): String = when (id) {
    LibraryId.Local -> "$documents/mybooks.db"
    is LibraryId.Account -> "$documents/databases/libraries/${id.userId}.db"
}

/** Opens a library's database: `mybooks.db` for the no-account library, `databases/libraries/<id>.db` for an account. */
fun openLibraryDatabase(documents: String, id: LibraryId): AppDatabase = openDatabaseFile(libraryDatabasePath(documents, id))

/** Opens the database file at [path] (a library's, or the upgrade's copy of one before it is put in place). */
fun openDatabaseFile(path: String): AppDatabase {
    path.toPath().parent?.let { FileSystem.SYSTEM.createDirectories(it) }
    return createDatabase(Room.databaseBuilder<AppDatabase>(name = path), BundledSQLiteDriver())
}

/** Deletes an account library's database files (db, -wal, -shm); the no-account library is never deleted. */
fun deleteLibraryDatabase(documents: String, id: LibraryId) {
    if (id !is LibraryId.Account) return
    val path = libraryDatabasePath(documents, id)
    for (suffix in listOf("", "-wal", "-shm")) FileSystem.SYSTEM.delete((path + suffix).toPath())
}
