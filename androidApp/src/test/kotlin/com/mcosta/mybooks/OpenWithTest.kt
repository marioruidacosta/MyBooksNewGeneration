package com.mcosta.mybooks

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.reader.IncomingFiles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** EPUB, PDF and .acsm files opened or shared from other apps come to MyBooks; nothing else does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenWithTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val main = ComponentName(context, MainActivity::class.java)

    private fun handles(intent: Intent) =
        context.packageManager.queryIntentActivities(intent.setPackage(context.packageName), PackageManager.MATCH_ALL).any { it.activityInfo.name == main.className }

    @Test
    fun booksOpenWithMyBooks() {
        val uri = Uri.parse("content://downloads/os-maias")
        for (type in listOf("application/epub+zip", "application/pdf", "application/vnd.adobe.adept+xml")) {
            assertTrue(type, handles(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)))
            assertTrue(type, handles(Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)))
        }
        assertFalse(handles(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/png")))
    }

    @Test
    fun theBookIsFoundInViewAndSendIntents() {
        val uri = Uri.parse("content://downloads/os-maias")
        assertEquals(uri, MainActivity.bookUri(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/epub+zip")))
        assertEquals(uri, MainActivity.bookUri(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)))
    }

    @Test
    fun nonBookTypesAreIgnored() {
        val uri = Uri.parse("content://media/photo")
        assertNull(MainActivity.bookUri(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)))
        assertNull(MainActivity.bookUri(Intent(Intent.ACTION_MAIN)))
    }

    /** Review focus: another app's permission ends with its intent, so the file is copied straight away. */
    @Test
    fun sharedFileIsCopiedAtOnce() = runBlocking {
        val original = File(context.filesDir, "os-maias.epub").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val copy = IncomingFiles.copy(context, Uri.fromFile(original))
        val copied = File(copy.path)
        assertEquals(File(context.cacheDir, "incoming"), copied.parentFile)
        assertArrayEquals(original.readBytes(), copied.readBytes())
        assertEquals("os-maias.epub", copy.fileName)
        assertEquals(4096L, copy.size)
    }

    @Test
    fun theAddEbookScreenCanBeOpenedFromOutside() {
        assertTrue(MainActivity.OPENABLE.matches("add-ebook/3k9x0p2v"))
        assertFalse(MainActivity.OPENABLE.matches("add-ebook/../settings"))
    }
}
