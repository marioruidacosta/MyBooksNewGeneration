package com.mcosta.mybooks.library

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.mcosta.mybooks.data.db.AppDatabase
import com.mcosta.mybooks.data.db.createDatabase
import java.io.File

/**
 * A library's database file (its -wal/-shm sit next to it). Room refuses a relative name containing "/", so account
 * databases are opened by absolute path.
 */
fun libraryDatabaseFile(context: Context, id: LibraryId): File {
    val dbDir = context.getDatabasePath("mybooks.db").parentFile!!
    return when (id) {
        LibraryId.Local -> File(dbDir, "mybooks.db")
        is LibraryId.Account -> File(dbDir, "libraries/${id.userId}.db")
    }
}

/** Opens a library's database: `mybooks.db` for the no-account library, `databases/libraries/<id>.db` for an account. */
fun openLibraryDatabase(context: Context, id: LibraryId): AppDatabase = openDatabaseFile(context, libraryDatabaseFile(context, id))

/** Opens the database file [file] (a library's, or the upgrade's copy of one before it is put in place). */
fun openDatabaseFile(context: Context, file: File): AppDatabase {
    file.parentFile?.mkdirs()
    return createDatabase(
        Room.databaseBuilder<AppDatabase>(context.applicationContext, file.absolutePath),
        AndroidSQLiteDriver(),
    )
}

/** Deletes an account library's database files (db, -wal, -shm); the no-account library is never deleted. */
fun deleteLibraryDatabase(context: Context, id: LibraryId) {
    if (id !is LibraryId.Account) return
    val file = libraryDatabaseFile(context, id)
    for (suffix in listOf("", "-wal", "-shm", "-journal")) File(file.path + suffix).delete()
}
