package com.mcosta.mybooks.data.lookup

import com.mcosta.mybooks.data.authors.AuthorBook
import com.mcosta.mybooks.data.authors.catalogTitleKey
import com.mcosta.mybooks.data.releases.OUTSIDE_SEARCH_LIMIT
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.IsbnQuery
import com.mcosta.mybooks.domain.isbnQuery
import com.mcosta.mybooks.domain.searchKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** No public catalogue could be searched. */
class PublicSearchUnavailable(cause: Throwable) : Exception(cause)

/**
 * Public catalogues searched for Search: by text in every [sources] at once (merged, each book once, Portuguese
 * editions first, at most [OUTSIDE_SEARCH_LIMIT]), or, for an ISBN, through [lookup] (the add-book lookup chain).
 * Throws [PublicSearchUnavailable] only when nothing could be searched.
 */
class PublicBookSearch(
    private val sources: List<BookTextSource>,
    private val lookup: suspend (isbn: String) -> LookupResult,
) {
    suspend fun search(query: String): List<AuthorBook> {
        (isbnQuery(query) as? IsbnQuery.Valid)?.let { return byIsbn(it.isbn13) }
        val answers = coroutineScope {
            sources.map { source ->
                async {
                    try {
                        Result.success(source.search(query))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
            }.awaitAll()
        }
        if (answers.isNotEmpty() && answers.all { it.isFailure }) throw PublicSearchUnavailable(answers.first().exceptionOrNull()!!)
        val seen = mutableSetOf<String>()
        return answers.flatMap { it.getOrDefault(emptyList()) }
            .filter { hit -> keys(hit.book).none { it in seen }.also { seen += keys(hit.book) } }
            .sortedByDescending { it.portuguese }
            .take(OUTSIDE_SEARCH_LIMIT)
            .map { it.book }
    }

    /** A book is the same as another with its ISBN, or with its title (before any subtitle) and first author. */
    private fun keys(book: AuthorBook): List<String> = listOfNotNull(
        book.isbn?.let { "isbn:$it" },
        "title:" + catalogTitleKey(book.title) + "|" + searchKey(book.authors.substringBefore(',').trim()),
    )

    private suspend fun byIsbn(isbn: String): List<AuthorBook> = when (val result = lookup(isbn)) {
        is LookupResult.Found -> listOf(result.book.toAuthorBook())
        LookupResult.NotFound, is LookupResult.NotFoundWithErrors -> emptyList()
        is LookupResult.Error -> throw PublicSearchUnavailable(result.cause)
    }

    private fun Book.toAuthorBook() = AuthorBook(title, authors, publishedYear, publisher, isbn, coverUrl, description, pageCount)
}
