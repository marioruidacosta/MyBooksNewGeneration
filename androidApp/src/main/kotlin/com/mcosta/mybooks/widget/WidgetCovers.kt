package com.mcosta.mybooks.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.mcosta.mybooks.data.covers.AndroidPhotoShrinker
import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.covers.PhotoShrinker
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.ui.components.coverColorArgb
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Cover pictures for the widget's cards: the book's photo, else its web cover (downloaded once, shrunk and kept in
 * [cacheDir], so the widget works offline), else a generated cover. Blocking: call it from the widget's card factory
 * (a binder thread), never the main thread.
 */
class WidgetCovers(
    private val cacheDir: File,
    private val store: () -> CoverStore,
    private val shrinker: PhotoShrinker = AndroidPhotoShrinker,
    private val download: (String) -> ByteArray? = ::downloadCover,
) {
    /** Never blank and never throws: anything that can't be shown becomes the generated cover. */
    fun bitmapFor(book: Book): Bitmap {
        val url = book.coverUrl?.takeIf { it.isNotBlank() }
        val bytes = when {
            url == null -> null
            store().isLocal(url) -> store().read(url)
            else -> webCover(url)
        }
        return bytes?.let { decode(it) } ?: generatedCoverBitmap(book.title, book.authors)
    }

    private fun webCover(url: String): ByteArray? = try {
        val file = File(File(cacheDir, DIR), sha1(url) + ".jpg")
        if (file.isFile) {
            file.readBytes()
        } else {
            download(url)?.let { shrinker.shrink(it, maxSide = MAX_SIDE) }?.also { shrunk ->
                file.parentFile?.mkdirs()
                file.writeBytes(shrunk)
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun decode(bytes: ByteArray): Bitmap? = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Exception) {
        null
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val DIR = "widget-covers"
        const val MAX_SIDE = 300
    }
}

/** A web cover's bytes, or null on any failure (no network, a timeout, a non-2xx answer). */
internal fun downloadCover(url: String): ByteArray? = try {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 10_000
    try {
        if (connection.responseCode in 200..299) connection.inputStream.use { it.readBytes() } else null
    } finally {
        connection.disconnect()
    }
} catch (_: Exception) {
    null
}

/** The app's generated cover, drawn as a bitmap: its colour for the title, a darker spine, the title and the author. */
fun generatedCoverBitmap(title: String, authors: String, width: Int = 200, height: Int = 300): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(coverColorArgb(title))
    canvas.drawRect(0f, 0f, width * 0.07f, height.toFloat(), Paint().apply { color = Color.argb(56, 0, 0, 0) })
    val left = width * 0.14f
    val textWidth = (width - left - width * 0.08f).toInt()
    val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = width * 0.11f
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    val titleLayout = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, textWidth)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).build()
    canvas.save()
    canvas.translate(left, height * 0.08f)
    titleLayout.draw(canvas)
    canvas.restore()
    if (authors.isNotBlank()) {
        val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 255, 255); textSize = width * 0.07f }
        val author = TextUtils.ellipsize(authors, authorPaint, textWidth.toFloat(), TextUtils.TruncateAt.END).toString()
        canvas.drawText(author, left, height * 0.92f, authorPaint)
    }
    return bitmap
}
