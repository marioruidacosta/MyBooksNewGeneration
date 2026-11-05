package com.mcosta.mybooks.platform

import com.mcosta.mybooks.data.covers.PhotoShrinker

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

/** Everything the shared UI needs from the platform, created by each app shell. */
class PlatformServices(
    val scanner: IsbnScanner,
    val camera: CoverCamera,
    val shrinker: PhotoShrinker,
    val documents: DocumentPicker,
    /** Null where reading text from photos isn't available (quotes are then typed). */
    val pageReader: PageReader? = null,
)
