package com.mcosta.mybooks.widget

import android.graphics.Bitmap
import android.graphics.Color
import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.covers.PhotoShrinker
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.ui.components.coverColorArgb
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WidgetCoversTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val cache by lazy { temp.newFolder("cache") }
    private val store by lazy { CoverStore(FileSystem.SYSTEM, temp.newFolder("covers").toOkioPath()) }
    private val jpeg: ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }.compress(Bitmap.CompressFormat.JPEG, 90, it)
    }.toByteArray()
    private val shrinkSides = mutableListOf<Int>()
    private val shrinker = object : PhotoShrinker {
        override fun shrink(photo: ByteArray, maxSide: Int): ByteArray { shrinkSides += maxSide; return jpeg }
    }
    private var downloads = 0
    private var downloaded: ByteArray? = byteArrayOf(1, 2, 3)

    private var now = 1_000_000L
    private val failures = mutableMapOf<String, Long>()
    private fun covers() = WidgetCovers(cache, { store }, shrinker, failures = failures, now = { now }) { downloads++; downloaded }
    private fun cachedFiles(): List<File> = File(cache, "widget-covers").listFiles()?.toList().orEmpty()

    /** A generated cover is the app's colour for the title; the right-middle avoids the spine and the text. */
    private fun assertGenerated(bitmap: Bitmap, title: String) =
        assertEquals(coverColorArgb(title), bitmap.getPixel(bitmap.width * 9 / 10, bitmap.height / 2))

    @Test
    fun aPhotoCoverIsReadFromTheStore() {
        val ref = store.save(jpeg)
        val bitmap = covers().bitmapFor(Book(title = "Stoner", coverUrl = ref))
        val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)   // the photo's red (JPEG shifts it a little)
        assertTrue(Color.red(pixel) > 200 && Color.green(pixel) < 50 && Color.blue(pixel) < 50)
        assertEquals(0, downloads)
    }

    /** A cover photo is kept at up to 1000 px; the widget only needs a small cover, so it gets one. */
    @Test
    fun aPhotoCoverIsShrunkToTheWidgetSize() {
        val big = ByteArrayOutputStream().also {
            Bitmap.createBitmap(1000, 1500, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }.compress(Bitmap.CompressFormat.JPEG, 80, it)
        }.toByteArray()
        val bitmap = covers().bitmapFor(Book(title = "Stoner", coverUrl = store.save(big)))
        assertTrue("${bitmap.width}x${bitmap.height}", maxOf(bitmap.width, bitmap.height) <= 300)
    }

    /** Covers come square, tall or wide; in the stack every card must look the same, so every cover is 2:3. */
    @Test
    fun everyCoverHasTheSameShape() {
        fun photo(w: Int, h: Int) = ByteArrayOutputStream().also {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }.compress(Bitmap.CompressFormat.JPEG, 80, it)
        }.toByteArray()
        val shapes = listOf(photo(600, 600), photo(400, 900), photo(900, 500)).map {
            covers().bitmapFor(Book(title = "Stoner", coverUrl = store.save(it))).let { b -> b.width to b.height }
        } + generatedCoverBitmap("Stoner", "John Williams").let { it.width to it.height }
        assertEquals(listOf(200 to 300), shapes.distinct())
    }

    /** A cover site that is down: every refresh would otherwise wait on it again for each card. */
    @Test
    fun aFailedDownloadIsNotRetriedStraightAway() {
        downloaded = null
        val book = Book(title = "Stoner", coverUrl = "https://covers.example/slow.jpg")
        covers().bitmapFor(book)
        now += 10 * 60_000L
        covers().bitmapFor(book)
        assertEquals(1, downloads)
    }

    @Test
    fun aFailedDownloadIsRetriedLater() {
        downloaded = null
        val book = Book(title = "Stoner", coverUrl = "https://covers.example/slow.jpg")
        covers().bitmapFor(book)
        now += 31 * 60_000L
        downloaded = byteArrayOf(1)
        covers().bitmapFor(book)
        assertEquals(2, downloads)
        assertEquals(1, cachedFiles().size)
    }

    @Test
    fun aDownloadedCoverIsShrunkAndKept() {
        covers().bitmapFor(Book(title = "Stoner", coverUrl = "https://covers.example/stoner.jpg"))
        assertEquals(listOf(300), shrinkSides)
        assertEquals(1, cachedFiles().count { it.name.endsWith(".jpg") })
    }

    @Test
    fun aCachedCoverIsNotDownloadedAgain() {
        val book = Book(title = "Stoner", coverUrl = "https://covers.example/stoner.jpg")
        covers().bitmapFor(book)
        covers().bitmapFor(book)
        assertEquals(1, downloads)
    }

    @Test
    fun aFailedDownloadGivesTheGeneratedCover() {
        downloaded = null
        val bitmap = covers().bitmapFor(Book(title = "Stoner", authors = "John Williams", coverUrl = "https://covers.example/gone.jpg"))
        assertGenerated(bitmap, "Stoner")
        assertEquals(0, cachedFiles().size)
    }

    @Test
    fun noCoverGivesTheGeneratedCover() {
        val bitmap = covers().bitmapFor(Book(title = "Stoner", authors = "John Williams", coverUrl = null))
        assertGenerated(bitmap, "Stoner")
        assertEquals(0, downloads)
    }
}
