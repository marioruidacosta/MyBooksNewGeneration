package com.mcosta.mybooks.platform

import com.mcosta.mybooks.data.covers.PhotoShrinker
import androidx.compose.ui.graphics.ImageBitmap
import com.mcosta.mybooks.domain.EbookFormat
import com.mcosta.mybooks.domain.EbookMetadata

sealed interface ScanOutcome {
    data class Isbn(val isbn: String) : ScanOutcome
    data object NotAnIsbn : ScanOutcome
    data object Cancelled : ScanOutcome
    data class Failed(val message: String) : ScanOutcome
}

/** Scans a book's barcode with the platform's scanner UI. */
interface IsbnScanner {
    suspend fun scan(): ScanOutcome
}

/** Takes a photo with the platform camera UI; null if the user cancelled. Throws with a message if unavailable. */
interface CoverCamera {
    suspend fun takePhoto(): ByteArray?
}

/** Lets the user pick where to save a document, or which document to open; false/null if cancelled. */
interface DocumentPicker {
    suspend fun save(suggestedName: String, mime: String, bytes: ByteArray): Boolean
    suspend fun open(mimeTypes: List<String>): ByteArray?
}

/** Reads the text in a photo of a page, on the phone. Throws with a message if it can't. */
interface PageReader {
    suspend fun read(photo: ByteArray): String
}

/** Shares a picture through the platform's share sheet. Throws with a message if it can't. */
interface ImageSharer {
    suspend fun share(image: ImageBitmap, name: String)
}

/**
 * Runs the monthly New in Portugal check in the background. [enable] asks for permission to notify where needed and
 * returns false if it isn't given.
 */
interface MonthlyAlerts {
    suspend fun enable(): Boolean
    fun disable()
}

/** A file handed to MyBooks (picked, or opened or shared from another app), copied where the app owns it. */
data class IncomingFile(val path: String, val fileName: String, val size: Long)

/** What a file turned out to be. */
sealed interface Inspection {
    data class Readable(val format: EbookFormat, val metadata: EbookMetadata) : Inspection
    /** Adobe DRM (Adobe Digital Editions) or an `.acsm` download ticket; [metadata] when the file tells. */
    data class AdobeProtected(val metadata: EbookMetadata?) : Inspection
    data object Protected : Inspection
    data object Unreadable : Inspection
}

/** Ebooks on this platform: picking and checking files, and the reader. */
interface EbookPlatform {
    /** Lets the user choose an EPUB, PDF or `.acsm` file; null if cancelled. */
    suspend fun pickFile(): IncomingFile?
    suspend fun inspect(file: IncomingFile): Inspection
    fun openReader(bookId: Long)
}

/** Everything the shared UI needs from the platform, created by each app shell. */
class PlatformServices(
    val scanner: IsbnScanner,
    val camera: CoverCamera,
    val shrinker: PhotoShrinker,
    val documents: DocumentPicker,
    /** Null where reading text from photos isn't available (quotes are then typed). */
    val pageReader: PageReader? = null,
    /** Null where sharing pictures isn't available (the Share button is then hidden). */
    val imageSharer: ImageSharer? = null,
    /** Null where background notifications aren't available (the setting is then hidden). */
    val monthlyAlerts: MonthlyAlerts? = null,
    /** Null where there is no ebook reader (iPhone for now): reading options are then hidden. */
    val ebooks: EbookPlatform? = null,
    /** Shares text through the platform's share sheet (BookNerd's abstracts); null hides Share. */
    val shareText: (suspend (String) -> Unit)? = null,
)
