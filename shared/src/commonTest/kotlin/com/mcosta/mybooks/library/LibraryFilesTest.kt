package com.mcosta.mybooks.library

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryFilesTest {
    private val fs = FakeFileSystem()
    private val filesDir = "/files".toPath()
    private val files = LibraryFiles(fs, filesDir)

    @Test
    fun localPathsAreTodays() {
        assertEquals(filesDir / "covers", files.coversDir(LibraryId.Local))
        assertEquals(filesDir / "ebooks", files.ebooksDir(LibraryId.Local))
    }

    @Test
    fun accountPathsAreUnderLibraries() {
        val id = LibraryId.Account("u1")
        assertEquals(filesDir / "libraries" / "u1" / "covers", files.coversDir(id))
        // Ebooks stay outside the backed-up libraries folder (backup quota).
        assertEquals(filesDir / "ebooks" / "u1", files.ebooksDir(id))
    }

    @Test
    fun deleteAllRemovesOnlyThatAccount() {
        val a = LibraryId.Account("a")
        val b = LibraryId.Account("b")
        val all = listOf(LibraryId.Local, a, b)
        for (id in all) {
            fs.createDirectories(files.coversDir(id))
            fs.write(files.coversDir(id) / "x.jpg") { writeUtf8("x") }
            fs.createDirectories(files.ebooksDir(id))
            fs.write(files.ebooksDir(id) / "b.epub") { writeUtf8("b") }
        }
        files.deleteAll(a)
        assertTrue(!fs.exists(files.coversDir(a)))
        assertTrue(!fs.exists(filesDir / "libraries" / "a"))
        assertTrue(!fs.exists(files.ebooksDir(a)))
        assertTrue(fs.exists(files.coversDir(b) / "x.jpg"))
        assertTrue(fs.exists(files.ebooksDir(b) / "b.epub"))
        assertTrue(fs.exists(files.ebooksDir(LibraryId.Local) / "b.epub"))
        assertTrue(fs.exists(files.coversDir(LibraryId.Local) / "x.jpg"))
        files.deleteAll(LibraryId.Local)
        assertTrue(fs.exists(files.coversDir(LibraryId.Local) / "x.jpg"))
    }

    @Test
    fun libraryIdRoundTrips() {
        assertEquals(LibraryId.Local, libraryIdOf(LibraryId.Local.key))
        assertEquals(LibraryId.Account("u1"), libraryIdOf(LibraryId.Account("u1").key))
        assertEquals("local", LibraryId.Local.key)
    }
}
