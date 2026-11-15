package com.mcosta.mybooks.ui.detail

import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthorPanelLoaderTest {
    private val repo = BookRepository(FakeBookDao(), object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
    })

    /** A New in Portugal page lists "Kang Han" (the National Library's "Han, Kang"): her books here are still hers. */
    @Test
    fun theAuthorsOtherBooksAreFoundWhateverTheNameOrder() = runTest {
        repo.save(Book(title = "Lições de Grego", authors = "Han Kang"))
        repo.save(Book(title = "Atos Humanos", authors = "Han Kang, Maria do Carmo Figueira"))
        repo.save(Book(title = "Encruzilhadas", authors = "Jonathan Franzen"))
        val loader = AuthorPanelLoader(repo, authors = null)
        loader.load("Kang Han") { false }
        assertEquals(listOf("Atos Humanos", "Lições de Grego"), loader.panel.value!!.otherBooks.map { it.title }.sorted())
    }
}
