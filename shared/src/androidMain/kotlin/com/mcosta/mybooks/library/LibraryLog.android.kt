package com.mcosta.mybooks.library

import android.util.Log

internal actual fun logLibraryError(message: String, e: Throwable) {
    runCatching { Log.w("MyBooks", message, e) }
}
