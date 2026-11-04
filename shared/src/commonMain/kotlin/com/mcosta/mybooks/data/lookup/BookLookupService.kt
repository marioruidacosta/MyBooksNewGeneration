package com.mcosta.mybooks.data.lookup

import com.mcosta.mybooks.domain.Book

/** Finds book metadata for an ISBN-13. */
interface BookLookupService {
    suspend fun lookup(isbn: String): LookupResult

    /** The book only if this source has a cover for it (the cover search needs nothing else); null otherwise. */
    suspend fun lookupCover(isbn: String): Book? = (lookup(isbn) as? LookupResult.Found)?.book?.takeIf { it.coverUrl != null }

    /** The book's description, if this source has one. */
    suspend fun lookupDescription(isbn: String): String? =
        (lookup(isbn) as? LookupResult.Found)?.book?.description?.takeIf { it.isNotBlank() }
}

sealed interface LookupResult {
    data class Found(val book: Book) : LookupResult
    data object NotFound : LookupResult

    /** No source had the book, but at least one source couldn't be checked because of [cause]. */
    data class NotFoundWithErrors(val cause: Throwable) : LookupResult

    /** Every source failed; [cause] is the first failure. */
    data class Error(val cause: Throwable) : LookupResult
}
