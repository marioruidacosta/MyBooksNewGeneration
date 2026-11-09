package com.mcosta.mybooks.library

/** Which library (database plus covers and ebooks folders) is open: the no-account one or one account's. */
sealed interface LibraryId {
    val key: String

    data object Local : LibraryId {
        override val key = "local"
    }

    data class Account(val userId: String) : LibraryId {
        override val key = userId
    }
}

fun libraryIdOf(key: String): LibraryId = if (key == LibraryId.Local.key) LibraryId.Local else LibraryId.Account(key)
