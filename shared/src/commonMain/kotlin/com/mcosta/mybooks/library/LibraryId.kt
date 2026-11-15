package com.mcosta.mybooks.library

/** Which library (database plus covers and ebooks folders) is open: the no-account one or one account's. */
sealed interface LibraryId {
    val key: String

    data object Local : LibraryId {
        override val key = "local"
    }

    /** The id becomes a folder and file name, so it is restricted to characters that can never escape the libraries folders. */
    data class Account(val userId: String) : LibraryId {
        init {
            require(userId.isNotBlank() && userId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
                "Invalid account library id"
            }
            require(!userId.equals(Local.key, ignoreCase = true)) { "Reserved library id" }
        }

        override val key = userId
    }
}

/** "local" is the no-account library; any other key must be a valid account id (throws IllegalArgumentException). */
fun libraryIdOf(key: String): LibraryId = if (key == LibraryId.Local.key) LibraryId.Local else LibraryId.Account(key)
