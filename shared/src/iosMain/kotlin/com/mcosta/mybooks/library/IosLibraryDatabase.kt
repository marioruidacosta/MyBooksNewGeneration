package com.mcosta.mybooks.library

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.mcosta.mybooks.data.db.AppDatabase
import com.mcosta.mybooks.data.db.createDatabase
import okio.FileSystem
import okio.Path.Companion.toPath

private fun databasePath(documents: String, id: LibraryId): String = when (id) {
    LibraryId.Local -> "$documents/mybooks.db"
    is LibraryId.Account -> "$documents/databases/libraries/${id.userId}.db"
}

/** Opens a library's database: `mybooks.db` for the no-account library, `databases/libraries/<id>.db` for an account. */
fun openLibraryDatabase(documents: String, id: LibraryId): AppDatabase {
    val path = databasePath(documents, id)
    path.toPath().parent?.let { FileSystem.SYSTEM.createDirectories(it) }
    return createDatabase(Room.databaseBuilder<AppDatabase>(name = path), BundledSQLiteDriver())
}

/** Deletes an account library's database files (db, -wal, -shm); the no-account library is never deleted. */
fun deleteLibraryDatabase(documents: String, id: LibraryId) {
    if (id !is LibraryId.Account) return
    val path = databasePath(documents, id)
    for (suffix in listOf("", "-wal", "-shm")) FileSystem.SYSTEM.delete((path + suffix).toPath())
}
