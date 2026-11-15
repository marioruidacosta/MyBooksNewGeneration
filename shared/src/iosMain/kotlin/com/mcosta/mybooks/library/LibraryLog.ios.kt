package com.mcosta.mybooks.library

internal actual fun logLibraryError(message: String, e: Throwable) {
    println("MyBooks: $message: $e")
}
