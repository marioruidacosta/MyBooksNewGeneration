package com.mcosta.mybooks.platform

import com.mcosta.mybooks.data.covers.PhotoShrinker
import androidx.compose.ui.graphics.ImageBitmap

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
)
