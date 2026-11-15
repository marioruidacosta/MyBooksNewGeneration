package com.mcosta.mybooks.platform

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    /** A cloud-only or deleted photo: the person gets a message, not a crash. */
    @Test
    fun aPhotoThatCantBeOpenedFails() {
        val uri = Uri.parse("content://media/picker/0/photo/gone")
        val e = assertThrows(IllegalStateException::class.java) { readPickedPhoto(resolver, uri) }
        assertEquals("The photo couldn't be opened", e.message)
    }
}
