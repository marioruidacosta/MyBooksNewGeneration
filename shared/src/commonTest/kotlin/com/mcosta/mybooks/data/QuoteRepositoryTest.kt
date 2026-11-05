package com.mcosta.mybooks.data

import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.MAX_QUOTE_LENGTH
import com.mcosta.mybooks.domain.Quote
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class QuoteRepositoryTest {
    private val quoteDao = FakeQuoteDao()
    private val quotes = QuoteRepository(quoteDao, clock = { 1000L })
    private val noLookup = object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
    }
    private val repo = BookRepository(FakeBookDao(), noLookup, clock = { 1000L }, quotes = quoteDao)

    private suspend fun saved(book: Book) = (repo.save(book) as SaveResult.Saved).id

    @Test
    fun quotesAreInPageOrderWithUnpagedOnesLast() = runTest {
        quotes.add(1, "Later", 120)
        quotes.add(1, "No page", null)
        quotes.add(1, "  Early  ", 12)
        quotes.add(2, "Another book", 1)
        assertEquals(listOf("Early" to 12, "Later" to 120, "No page" to null), quotes.observe(1).first().map { it.text to it.page })
    }

    @Test
    fun blankQuotesAreNotSavedAndLongOnesAreCut() = runTest {
        assertFalse(quotes.add(1, "   ", 3))
        quotes.add(1, "x".repeat(MAX_QUOTE_LENGTH + 50), 0)
        val q = quotes.observe(1).first().single()
        assertEquals(MAX_QUOTE_LENGTH, q.text.length)
        assertEquals(null, q.page)                 // page 0 means none
        assertFalse(quotes.update(q.copy(text = " ")))
        quotes.update(q.copy(text = "Short", page = 7))
        assertEquals(listOf(Quote(q.id, 1, "Short", 7, 1000)), quotes.observe(1).first())
    }

    @Test
    fun deletingABookDeletesItsQuotes() = runTest {
        val id = saved(Book(title = "Dune"))
        val other = saved(Book(title = "Emma"))
        quotes.add(id, "Fear is the mind-killer.", 8)
        quotes.add(other, "It is a truth universally acknowledged…", 1)
        repo.delete(id)
        assertEquals(listOf("It is a truth universally acknowledged…"), quoteDao.getAll().map { it.text })
    }

    @Test
    fun backupKeepsQuotesAndRestoringTwiceDoesNotRepeatThem() = runTest {
        val id = saved(Book(title = "Dune", isbn = "9780441013593"))
        quotes.add(id, "Fear is the mind-killer.", 8)
        quotes.add(id, "The spice must flow.", null)
        val backup = repo.exportJson()

        val otherQuotes = FakeQuoteDao()
        val other = BookRepository(FakeBookDao(), noLookup, quotes = otherQuotes)
        other.importJson(backup)
        other.importJson(backup)
        val restoredId = other.findByIsbn("9780441013593")!!.id
        assertEquals(
            listOf("Fear is the mind-killer." to 8, "The spice must flow." to null),
            otherQuotes.forBook(restoredId).map { it.text to it.page },
        )
    }
}
