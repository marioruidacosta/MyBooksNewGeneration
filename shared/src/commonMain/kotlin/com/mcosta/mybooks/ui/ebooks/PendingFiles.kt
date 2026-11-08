package com.mcosta.mybooks.ui.ebooks

import com.mcosta.mybooks.platform.IncomingFile
import kotlin.random.Random

/** Files waiting for the "Add an ebook" screen, by a short key that can travel in a route. Kept for the session. */
class PendingFiles {
    private val files = mutableMapOf<String, IncomingFile>()

    fun put(file: IncomingFile): String {
        val key = Random.nextLong().toULong().toString(36)
        files[key] = file
        return key
    }

    fun get(key: String): IncomingFile? = files[key]

    fun remove(key: String) {
        files.remove(key)
    }
}
