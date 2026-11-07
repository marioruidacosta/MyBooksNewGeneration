package com.mcosta.mybooks.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.mcosta.mybooks.platform.ActivityResults
import com.mcosta.mybooks.platform.EbookPlatform
import com.mcosta.mybooks.platform.IncomingFile
import com.mcosta.mybooks.platform.Inspection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/** Ebooks on Android: the system file picker, the file checks, and the reader ([openReader]). */
class AndroidEbookPlatform(
    private val activity: ComponentActivity,
    private val results: ActivityResults,
    private val openReader: (Long) -> Unit,
) : EbookPlatform {
    private val inspector = EbookInspector()

    override suspend fun pickFile(): IncomingFile? {
        val uri = results.launch(ActivityResultContracts.OpenDocument(), BOOK_TYPES) ?: return null
        return IncomingFiles.copy(activity, uri)
    }

    override suspend fun inspect(file: IncomingFile): Inspection = inspector.inspect(File(file.path), file.fileName)

    override fun openReader(bookId: Long) = openReader.invoke(bookId)

    companion object {
        /** EPUB, PDF and Adobe's .acsm; octet-stream too, as many apps label EPUBs that way. */
        val BOOK_TYPES = arrayOf("application/epub+zip", "application/pdf", "application/vnd.adobe.adept+xml", "application/octet-stream")
    }
}

/** Files handed to MyBooks, copied at once into `cacheDir/incoming/` (another app's permission to read them ends soon). */
object IncomingFiles {
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    suspend fun copy(context: Context, uri: Uri): IncomingFile = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "incoming").apply { mkdirs() }
        // Copies left behind (the app closed before they were added) are cleared after a day.
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > DAY_MS }?.forEach { it.delete() }
        val name = displayName(context, uri)
        val target = File(dir, "${System.currentTimeMillis()}-${name.replace(Regex("""[\\/:*?"<>|]"""), "_")}")
        val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("Can't open $uri")
        input.use { from -> target.outputStream().use { from.copyTo(it) } }
        IncomingFile(target.path, name, target.length())
    }

    private fun displayName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "book"
}
