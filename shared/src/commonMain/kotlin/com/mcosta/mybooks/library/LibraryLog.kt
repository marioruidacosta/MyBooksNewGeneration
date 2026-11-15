package com.mcosta.mybooks.library

/** Logs a library failure nobody sees otherwise (logcat on Android, the console on iOS). */
internal expect fun logLibraryError(message: String, e: Throwable)
