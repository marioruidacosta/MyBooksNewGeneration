package com.mcosta.mybooks.platform

import android.content.ActivityNotFoundException
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.mcosta.mybooks.data.covers.AndroidPhotoShrinker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs activity-result contracts as suspend calls. A launcher is registered per call and
 * unregistered when the result arrives (results pending across activity recreation are lost).
 */
class ActivityResults(private val activity: ComponentActivity) {
    private val counter = AtomicInteger()

    suspend fun <I, O> launch(contract: ActivityResultContract<I, O>, input: I): O {
        val result = CompletableDeferred<O>()
        val key = "mybooks-result-${counter.incrementAndGet()}"
        val launcher = activity.activityResultRegistry.register(key, contract) { result.complete(it) }
        try {
            launcher.launch(input)
            return result.await()
        } finally {
            launcher.unregister()
        }
    }
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
fun androidPlatformServices(activity: ComponentActivity, monthlyAlerts: MonthlyAlerts? = null): PlatformServices {
    val results = ActivityResults(activity)
    return PlatformServices(
        scanner = AndroidIsbnScanner(activity),
        camera = AndroidCoverCamera(activity, results),
        shrinker = AndroidPhotoShrinker,
        documents = AndroidDocumentPicker(activity, results),
        pageReader = AndroidPageReader(),
        imageSharer = AndroidImageSharer(activity),
        monthlyAlerts = monthlyAlerts,
    )
}
