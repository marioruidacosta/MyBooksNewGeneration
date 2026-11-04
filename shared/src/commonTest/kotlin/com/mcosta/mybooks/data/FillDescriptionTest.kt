package com.mcosta.mybooks.data

import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FillDescriptionTest {
    private var asked = 0
    private val repo = BookRepository(FakeBookDao(), object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult {
            asked++
            return LookupResult.Found(Book(title = "x", description = "From Google Books."))
        }
    })

    private suspend fun saved(book: Book) = (repo.save(book) as SaveResult.Saved).id

    @Test
    fun aBookWithoutDescriptionGetsOneAndKeepsIt() = runTest {
        val id = saved(Book(title = "Dune", isbn = "9780441013593"))
        assertEquals(true, repo.fillDescription(id))
        assertEquals("From Google Books.", repo.getBook(id)!!.description)
        assertEquals(false, repo.fillDescription(id))            // already has one: nothing asked
        assertEquals(1, asked)
    }

    @Test
    fun existingDescriptionsAndBooksWithoutIsbnAreLeftAlone() = runTest {
        val described = saved(Book(title = "Emma", isbn = "9789722378482", description = "Mine."))
        val noIsbn = saved(Book(title = "Notes"))
        assertEquals(false, repo.fillDescription(described))
        assertEquals(false, repo.fillDescription(noIsbn))
        assertEquals("Mine.", repo.getBook(described)!!.description)
        assertEquals(0, asked)
    }
}
