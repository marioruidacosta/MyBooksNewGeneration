package com.mcosta.mybooks.platform

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mcosta.mybooks.data.covers.AndroidPhotoShrinker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads a page with ML Kit's text recognition from Play services: on the phone, nothing is uploaded. Latin script
 * (Portuguese, English and the like). The photo is turned upright and shrunk first, as for covers.
 */
class AndroidPageReader : PageReader {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun read(photo: ByteArray): String {
        val bitmap = withContext(Dispatchers.Default) {
            val upright = AndroidPhotoShrinker.shrink(photo, MAX_SIDE)
            requireNotNull(BitmapFactory.decodeByteArray(upright, 0, upright.size)) { "Couldn't read the photo" }
        }
        return suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { cont.resume(it.text) }
                .addOnFailureListener { e -> cont.resumeWithException(IllegalStateException(e.message ?: "Couldn't read the text", e)) }
        }
    }

    private companion object {
        /** Big enough for small print, small enough to read quickly. */
        const val MAX_SIDE = 2400
    }
}
