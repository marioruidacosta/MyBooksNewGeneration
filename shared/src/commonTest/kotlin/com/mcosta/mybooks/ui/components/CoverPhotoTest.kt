package com.mcosta.mybooks.ui.components

import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.covers.PhotoShrinker
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CoverPhotoTest {
    private val fs = FakeFileSystem()
    private val dir = "/app/files/covers".toPath()
    private val store = CoverStore(fs, dir)
    private val shrunk = mutableListOf<ByteArray>()
    private val shrinker = object : PhotoShrinker {
        override fun shrink(photo: ByteArray, maxSide: Int): ByteArray {
            shrunk += photo
            return byteArrayOf(9)
        }
    }

    /** A large gallery photo is stored the way a camera photo is: shrunk first. */
    @Test
    fun pickedPhotoIsShrunkStoredAndReferenced() = runTest {
        val result = storeCoverPhoto({ byteArrayOf(1, 2, 3) }, shrinker, store)
        val ref = assertIs<CoverPhotoResult.Stored>(result).ref
        assertTrue(ref.startsWith(CoverStore.PREFIX))
        assertContentEquals(byteArrayOf(1, 2, 3), shrunk.single())
        assertContentEquals(byteArrayOf(9), store.read(ref))
    }

    @Test
    fun cancelledPickChangesNothing() = runTest {
        assertEquals(CoverPhotoResult.Cancelled, storeCoverPhoto({ null }, shrinker, store))
        assertTrue(shrunk.isEmpty())
        assertFalse(fs.exists(dir) && fs.list(dir).isNotEmpty())
    }

    @Test
    fun unreadablePhotoGivesTheMessage() = runTest {
        val result = storeCoverPhoto({ throw IllegalStateException("bad image") }, shrinker, store)
        assertEquals(CoverPhotoResult.Failed("Couldn't use the photo: bad image"), result)
    }
}
