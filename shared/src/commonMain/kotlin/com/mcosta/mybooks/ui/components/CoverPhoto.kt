package com.mcosta.mybooks.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.covers.PhotoShrinker
import com.mcosta.mybooks.ui.LocalCoverStore
import com.mcosta.mybooks.ui.LocalPlatformServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What became of a cover photo: stored under a `cover:` reference, cancelled, or a message to show. */
internal sealed interface CoverPhotoResult {
    data class Stored(val ref: String) : CoverPhotoResult
    data object Cancelled : CoverPhotoResult
    data class Failed(val message: String) : CoverPhotoResult
}

/** Gets a [photo] (camera or picker; null if cancelled), shrinks it and stores it as a cover photo. */
internal suspend fun storeCoverPhoto(photo: suspend () -> ByteArray?, shrinker: PhotoShrinker, store: CoverStore): CoverPhotoResult =
    try {
        val bytes = photo()
        if (bytes == null) {
            CoverPhotoResult.Cancelled
        } else {
            CoverPhotoResult.Stored(withContext(Dispatchers.Default) { store.save(shrinker.shrink(bytes)) })
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CoverPhotoResult.Failed("Couldn't use the photo: ${e.message ?: "unknown error"}")
    }

/**
 * Returns a function that opens the platform camera to photograph a cover. On success the photo is
 * shrunk, stored, and [onPhoto] receives its `cover:` reference; [onError] gets a message otherwise.
 */
@Composable
fun rememberCoverPhotoTaker(onPhoto: (String) -> Unit, onError: (String) -> Unit): () -> Unit {
    val services = LocalPlatformServices.current
    return rememberCoverPhotoAction({ services.camera.takePhoto() }, onPhoto, onError)
}

/**
 * Like [rememberCoverPhotoTaker], with a photo chosen from the device's photos; null where choosing isn't available
 * (the option is then hidden).
 */
@Composable
fun rememberCoverPhotoChooser(onPhoto: (String) -> Unit, onError: (String) -> Unit): (() -> Unit)? {
    val picker = LocalPlatformServices.current.photoPicker
    val choose = rememberCoverPhotoAction({ picker?.pickPhoto() }, onPhoto, onError)
    return if (picker == null) null else choose
}

@Composable
private fun rememberCoverPhotoAction(photo: suspend () -> ByteArray?, onPhoto: (String) -> Unit, onError: (String) -> Unit): () -> Unit {
    val services = LocalPlatformServices.current
    val store = LocalCoverStore.current
    val scope = rememberCoroutineScope()
    val currentPhoto by rememberUpdatedState(photo)
    val currentOnPhoto by rememberUpdatedState(onPhoto)
    val currentOnError by rememberUpdatedState(onError)
    return {
        scope.launch {
            when (val result = storeCoverPhoto(currentPhoto, services.shrinker, store)) {
                is CoverPhotoResult.Stored -> currentOnPhoto(result.ref)
                is CoverPhotoResult.Failed -> currentOnError(result.message)
                CoverPhotoResult.Cancelled -> Unit
            }
        }
    }
}
