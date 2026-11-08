package com.mcosta.mybooks.data.lookup

import com.mcosta.mybooks.data.authors.AuthorBook
import com.mcosta.mybooks.domain.Book
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PublicBookSearchTest {
    private fun book(title: String, authors: String, isbn: String? = null) = AuthorBook(title, authors, null, null, isbn, null, null, null)
    private val x = book("Dune", "Frank Herbert", "9780441013593")
    private val y = book("Ensaio sobre a Cegueira: romance", "José Saramago")
    private val z = book("Ensaio Sobre a Cegueira", "José Saramago, Outro")

    private fun source(vararg hits: PublicHit) = BookTextSource { hits.toList() }
    private val failing = BookTextSource { throw HttpStatusException(429) }
    private val noLookup: suspend (String) -> LookupResult = { error("not an ISBN search") }

    @Test
    fun mergedWithoutDuplicatesPortugueseFirst() = runTest {
        val search = PublicBookSearch(
            listOf(source(PublicHit(x, false), PublicHit(y, true)), source(PublicHit(x, false), PublicHit(z, true))),
            noLookup,
        )
        assertEquals(listOf(y, x), search.search("cegueira"))
    }

    @Test
    fun atMost20() = runTest {
        val many = (1..30).map { PublicHit(book("Livro $it", "Autor $it"), false) }
        assertEquals(20, PublicBookSearch(listOf(BookTextSource { many }), noLookup).search("livro").size)
    }

    /** Review focus: Google Books answering 429 while Open Library works. */
    @Test
    fun oneSourceFailingKeepsTheOther() = runTest {
        assertEquals(listOf(z), PublicBookSearch(listOf(failing, source(PublicHit(z, true))), noLookup).search("cegueira"))
    }

    @Test
    fun allSourcesFailingThrows() = runTest {
        assertFailsWith<PublicSearchUnavailable> { PublicBookSearch(listOf(failing, failing), noLookup).search("cegueira") }
    }

    @Test
    fun isbnUsesTheLookupChain() = runTest {
        var asked: String? = null
        var textSearched = false
        val text = BookTextSource { textSearched = true; emptyList() }
        val found = Book(
            title = "Ensaio sobre a Cegueira", authors = "José Saramago", publishedYear = 1995, publisher = "Caminho",
            isbn = "9789722036504", coverUrl = "https://c/1.jpg", description = "Branca.", pageCount = 310,
        )
        val search = PublicBookSearch(listOf(text)) { isbn -> asked = isbn; LookupResult.Found(found) }
        assertEquals(
            listOf(AuthorBook("Ensaio sobre a Cegueira", "José Saramago", 1995, "Caminho", "9789722036504", "https://c/1.jpg", "Branca.", 310)),
            search.search("972-20-3650-5"),
        )
        assertEquals("9789722036504", asked)
        assertTrue(!textSearched)
        assertEquals(emptyList(), PublicBookSearch(listOf(text)) { LookupResult.NotFound }.search("972-20-3650-5"))
        assertEquals(emptyList(), PublicBookSearch(listOf(text)) { LookupResult.NotFoundWithErrors(Exception()) }.search("972-20-3650-5"))
        assertFailsWith<PublicSearchUnavailable> { PublicBookSearch(listOf(text)) { LookupResult.Error(Exception()) }.search("972-20-3650-5") }
    }

    /** Two different books with the same title and no author listed are both kept (nothing says they're the same). */
    @Test
    fun authorlessBooksWithTheSameTitleStay() = runTest {
        val a = book("Poemas", "")
        val b = book("Poemas", "", "9789722036504")
        assertEquals(listOf(a, b), PublicBookSearch(listOf(source(PublicHit(a, false)), source(PublicHit(b, false))), noLookup).search("poemas"))
    }
}
