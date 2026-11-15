package com.mcosta.mybooks.library

import okio.FileSystem
import okio.Path

/** Where each library keeps its files. The no-account library keeps today's folders; accounts' covers live under `libraries/<id>` and their ebooks under `ebooks/<id>`. */
class LibraryFiles(val fileSystem: FileSystem, val filesDir: Path) {
    private fun root(id: LibraryId): Path = when (id) {
        LibraryId.Local -> filesDir
        is LibraryId.Account -> filesDir / "libraries" / id.userId
    }

    fun coversDir(id: LibraryId): Path = root(id) / "covers"

    /** Account ebooks live under today's ebooks folder, outside the backed-up `libraries` tree (backup quota is 25 MB). */
    fun ebooksDir(id: LibraryId): Path = when (id) {
        LibraryId.Local -> filesDir / "ebooks"
        is LibraryId.Account -> filesDir / "ebooks" / id.userId
    }

    /** Deletes an account library's folder; the no-account library is never deleted. */
    fun deleteAll(id: LibraryId) {
        if (id is LibraryId.Account) {
            fileSystem.deleteRecursively(root(id))
            fileSystem.deleteRecursively(ebooksDir(id))
        }
    }
}
