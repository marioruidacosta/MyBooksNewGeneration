package com.mcosta.mybooks.data

import com.mcosta.mybooks.data.db.QuoteDao
import com.mcosta.mybooks.data.db.toEntity
import com.mcosta.mybooks.data.db.toQuote
import com.mcosta.mybooks.domain.MAX_QUOTE_LENGTH
import com.mcosta.mybooks.domain.Quote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/** Quotes saved from books. A quote is its text trimmed (at most [MAX_QUOTE_LENGTH]) and a page, if any, above 0. */
class QuoteRepository(
    private val dao: QuoteDao,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    fun observe(bookId: Long): Flow<List<Quote>> = dao.observeForBook(bookId).map { list -> list.map { it.toQuote() } }

    /** Saves a new quote; blank text is not saved (returns false). */
    suspend fun add(bookId: Long, text: String, page: Int?): Boolean {
        val clean = clean(text) ?: return false
        dao.insert(Quote(bookId = bookId, text = clean, page = page?.takeIf { it > 0 }, dateAdded = clock()).toEntity())
        return true
    }

    /** Saves text highlighted in the reader as a quote with its [position] in the ebook; null (nothing saved) for blank text. */
    suspend fun addHighlight(bookId: Long, text: String, position: String): Long? {
        val clean = clean(text) ?: return null
        return dao.insert(Quote(bookId = bookId, text = clean, dateAdded = clock(), position = position).toEntity())
    }

    /** Changes a quote's text and page; blank text is not saved (returns false). */
    suspend fun update(quote: Quote): Boolean {
        val clean = clean(quote.text) ?: return false
        dao.update(quote.copy(text = clean, page = quote.page?.takeIf { it > 0 }).toEntity())
        return true
    }

    suspend fun delete(id: Long) = dao.delete(id)

    /** Puts a deleted quote back as it was (Undo): its text, page, date and, for a highlight, its place in the ebook. */
    suspend fun restore(quote: Quote) {
        dao.insert(quote.copy(id = 0).toEntity())
    }

    private fun clean(text: String) = text.trim().take(MAX_QUOTE_LENGTH).trim().takeIf { it.isNotEmpty() }
}
