package com.mcosta.mybooks.data.lookup

import com.mcosta.mybooks.domain.Book
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Asks [sources] in order and returns the first book found. If that book has no cover, the remaining
 * sources are asked only for a cover, which is added to it.
 */
class LookupChain(private val sources: List<BookLookupService>) : BookLookupService {

    /**
     * Asks every source at once (a book takes as long as the slowest source, not the sum) and keeps the
     * earliest source's cover; once an earlier source has one, later ones aren't waited for.
     */
    /** Asks every source at once and keeps the earliest source's description (the National Library has none). */
    override suspend fun lookupDescription(isbn: String): String? = coroutineScope {
        val answers = sources.map { async { it.lookupDescription(isbn) } }
        var found: String? = null
        for (answer in answers) {
            found = answer.await()
            if (found != null) break
        }
        answers.forEach { it.cancel() }
        found
    }

    override suspend fun lookupCover(isbn: String): Book? = coroutineScope {
        val answers = sources.map { async { it.lookupCover(isbn) } }
        var found: Book? = null
        for (answer in answers) {
            found = answer.await()
            if (found != null) break
        }
        answers.forEach { it.cancel() }
        found
    }

    override suspend fun lookup(isbn: String): LookupResult {
        var firstError: Throwable? = null
        var anyMiss = false
        for ((index, source) in sources.withIndex()) {
            when (val result = source.lookup(isbn)) {
                is LookupResult.Found ->
                    return if (result.book.coverUrl != null) result else withCoverFrom(sources.drop(index + 1), isbn, result)
                LookupResult.NotFound -> anyMiss = true
                is LookupResult.NotFoundWithErrors -> { anyMiss = true; firstError = firstError ?: result.cause }
                is LookupResult.Error -> firstError = firstError ?: result.cause
            }
        }
        val error = firstError
        return when {
            error == null -> LookupResult.NotFound
            anyMiss -> LookupResult.NotFoundWithErrors(error)
            else -> LookupResult.Error(error)
        }
    }

    private suspend fun withCoverFrom(rest: List<BookLookupService>, isbn: String, found: LookupResult.Found): LookupResult.Found {
        for (source in rest) {
            val cover = (source.lookup(isbn) as? LookupResult.Found)?.book?.coverUrl
            if (cover != null) return LookupResult.Found(found.book.copy(coverUrl = cover))
        }
        return found
    }
}
