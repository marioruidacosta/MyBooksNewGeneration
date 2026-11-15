package com.mcosta.mybooks.platform

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidCoverPhotoPickerTest {
    private val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver

    @Test
    fun aPickedPhotoIsRead() {
        val uri = Uri.parse("content://media/picker/0/photo/1")
        shadowOf(resolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(4, 5)))
        assertArrayEquals(byteArrayOf(4, 5), readPickedPhoto(resolver, uri))
    }

    /** A huge file is refused with a message: reading it whole could exhaust memory and crash the app. */
    @Test
    fun aPhotoTooLargeToHoldIsRefused() {
        val uri = Uri.parse("content://media/picker/0/photo/huge")
        shadowOf(resolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)))
        val e = assertThrows(IllegalStateException::class.java) { readPickedPhoto(resolver, uri, maxBytes = 4) }
        assertEquals("The photo is too large", e.message)
    }

    @Test
    fun aPhotoAtTheLimitIsRead() {
        val uri = Uri.parse("content://media/picker/0/photo/limit")
        shadowOf(resolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), readPickedPhoto(resolver, uri, maxBytes = 4))
    }

    /**
     * A rotation while the picker or camera is open recreates the activity and its ActivityResults. The registry keeps
     * the result under the old key; if the new instance reused it, the next launch would get that stale result.
     */
    @Test
    fun resultKeysAreNotReusedAfterTheActivityIsRecreated() {
        assertNotEquals(ResultKeys().next(), ResultKeys().next())
    }

    /** A cloud-only or deleted photo: the person gets a message, not a crash. */
    @Test
    fun aPhotoThatCantBeOpenedFails() {
        val uri = Uri.parse("content://media/picker/0/photo/gone")
        val e = assertThrows(IllegalStateException::class.java) { readPickedPhoto(resolver, uri) }
        assertEquals("The photo couldn't be opened", e.message)
    }
}
