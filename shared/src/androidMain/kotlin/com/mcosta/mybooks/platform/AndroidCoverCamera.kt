package com.mcosta.mybooks.platform

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.mcosta.mybooks.data.covers.AndroidPhotoShrinker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs activity-result contracts as suspend calls. A launcher is registered per call and
 * unregistered when the result arrives (results pending across activity recreation are lost).
 */
class ActivityResults(private val activity: ComponentActivity) {
    private val keys = ResultKeys()

    suspend fun <I, O> launch(contract: ActivityResultContract<I, O>, input: I): O {
        val result = CompletableDeferred<O>()
        val key = keys.next()
        val launcher = activity.activityResultRegistry.register(key, contract) { result.complete(it) }
        try {
            launcher.launch(input)
            return result.await()
        } finally {
            launcher.unregister()
        }
    }
}

/**
 * Keys for activity-result launches, unique across activity instances: after a rotation the registry still holds a
 * result under the old instance's key, and a new instance reusing that key would hand the stale result to its next
 * launch (a wrong cover, or a lost camera photo).
 */
internal class ResultKeys {
    private val prefix = "mybooks-result-${UUID.randomUUID()}"
    private val counter = AtomicInteger()
    fun next(): String = "$prefix-${counter.incrementAndGet()}"
}

/** Opens the phone's camera app (no CAMERA permission needed) and returns the photo's bytes. */
class AndroidCoverCamera(private val activity: ComponentActivity, private val results: ActivityResults) : CoverCamera {
    override suspend fun takePhoto(): ByteArray? {
        val file = File(activity.cacheDir, "camera").apply { mkdirs() }.let { File(it, "cover-${System.currentTimeMillis()}.jpg") }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        try {
            val saved = try {
                results.launch(ActivityResultContracts.TakePicture(), uri)
            } catch (e: ActivityNotFoundException) {
                throw IllegalStateException("No camera app found")
            }
            if (!saved) return null
            return withContext(Dispatchers.IO) { file.readBytes() }
        } finally {
            withContext(Dispatchers.IO) { file.delete() }
        }
    }
}

/** Opens the system photo picker (images only, no permission needed) and returns the chosen photo's bytes. */
class AndroidCoverPhotoPicker(private val activity: ComponentActivity, private val results: ActivityResults) : CoverPhotoPicker {
    override suspend fun pickPhoto(): ByteArray? {
        val uri = results.launch(
            ActivityResultContracts.PickVisualMedia(),
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        ) ?: return null
        return withContext(Dispatchers.IO) { readPickedPhoto(activity.contentResolver, uri) }
    }
}

/**
 * The bytes of a picked photo; throws with a message when it can't be opened (a cloud-only or deleted photo) or is over
 * [maxBytes] (reading a huge file whole could run out of memory, which would crash the app instead).
 */
internal fun readPickedPhoto(resolver: ContentResolver, uri: Uri, maxBytes: Int = MAX_PICKED_PHOTO_BYTES): ByteArray {
    val stream = try {
        resolver.openInputStream(uri)
    } catch (_: FileNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    } ?: throw IllegalStateException("The photo couldn't be opened")
    return stream.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > maxBytes) throw IllegalStateException("The photo is too large")
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    }
}

internal const val MAX_PICKED_PHOTO_BYTES = 40 * 1024 * 1024

/** Storage Access Framework pickers for backup export/import. */
class AndroidDocumentPicker(private val activity: ComponentActivity, private val results: ActivityResults) : DocumentPicker {
    override suspend fun save(suggestedName: String, mime: String, bytes: ByteArray): Boolean {
        val uri = results.launch(ActivityResultContracts.CreateDocument(mime), suggestedName) ?: return false
        withContext(Dispatchers.IO) {
            val out = activity.contentResolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException("Can't open $uri")
            out.use { it.write(bytes) }
        }
        return true
    }

    override suspend fun open(mimeTypes: List<String>): ByteArray? {
        val uri = results.launch(ActivityResultContracts.OpenDocument(), (mimeTypes + "*/*").toTypedArray()) ?: return null
        return withContext(Dispatchers.IO) {
            val input = activity.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("Can't open $uri")
            input.use { it.readBytes() }
        }
    }
}

/** The Android implementations of everything the shared UI needs from the platform. */
fun androidPlatformServices(
    activity: ComponentActivity,
    monthlyAlerts: MonthlyAlerts? = null,
    /** The ebook reader, made with the same [ActivityResults] as the other pickers. */
    ebooks: ((ActivityResults) -> EbookPlatform)? = null,
): PlatformServices {
    val results = ActivityResults(activity)
    return PlatformServices(
        scanner = AndroidIsbnScanner(activity),
        camera = AndroidCoverCamera(activity, results),
        shrinker = AndroidPhotoShrinker,
        documents = AndroidDocumentPicker(activity, results),
        pageReader = AndroidPageReader(),
        imageSharer = AndroidImageSharer(activity),
        shareText = { text ->
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
            activity.startActivity(android.content.Intent.createChooser(send, null))
        },
        monthlyAlerts = monthlyAlerts,
        ebooks = ebooks?.invoke(results),
        photoPicker = AndroidCoverPhotoPicker(activity, results),
    )
}
