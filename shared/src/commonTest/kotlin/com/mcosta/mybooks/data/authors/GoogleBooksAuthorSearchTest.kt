package com.mcosta.mybooks.data.authors

import com.mcosta.mybooks.data.lookup.FakeHttpServer
import com.mcosta.mybooks.data.lookup.FakeResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoogleBooksAuthorSearchTest {
    private val server = FakeHttpServer()
    private val search = GoogleBooksAuthorSearch(server.httpClient(), apiKey = "KEY", baseUrl = server.baseUrl)

    private fun item(title: String, authors: List<String>, isbn: String? = null, year: String? = null, cover: String? = null) =
        """{"volumeInfo":{"title":"$title","authors":[${authors.joinToString(",") { "\"$it\"" }}]""" +
            (year?.let { ""","publishedDate":"$it"""" } ?: "") +
            (isbn?.let { ""","industryIdentifiers":[{"type":"ISBN_10","identifier":"0000000000"},{"type":"ISBN_13","identifier":"$it"}]""" } ?: "") +
            (cover?.let { ""","imageLinks":{"thumbnail":"$it"}""" } ?: "") +
            ""","publisher":"Porto Editora","description":"About $title.","pageCount":300}}"""

    private fun page(vararg items: String) =
        FakeResponse().setHeader("Content-Type", "application/json").setBody("""{"totalItems":${items.size},"items":[${items.joinToString(",")}]}""")

    @Test
    fun portugueseEditionsFirstThenOthersWithoutRepeats() = runTest {
        server.enqueue(
            page(
                item("Memorial do Convento", listOf("José Saramago"), "9789720046857", "1982", "http://books.google.com/m.jpg"),
                item("Ensaio sobre a Cegueira", listOf("José Saramago"), "9789722113922", "1995"),
                item("Saramago: uma biografia", listOf("Someone Else")),                         // about him, not by him
            ),
        )
        server.enqueue(
            page(
                item("Blindness", listOf("Jose Saramago", "Giovanni Pontiero"), "9780156007757", "1999"),
                item("Ensaio Sobre a Cegueira: romance", listOf("José Saramago"), "9789720000000", "2001"),  // same book
            ),
        )
        val books = search.booksBy("José Saramago")
        assertEquals(listOf("Memorial do Convento", "Ensaio sobre a Cegueira", "Blindness"), books.map { it.title })
        val memorial = books.first()
        assertEquals("9789720046857", memorial.isbn)
        assertEquals(1982, memorial.year)
        assertEquals("https://books.google.com/m.jpg", memorial.coverUrl)
        assertEquals("Porto Editora", memorial.publisher)
        assertEquals("About Memorial do Convento.", memorial.description)
        assertEquals("José Saramago", memorial.authors)
        val first = server.takeRequest().path
        assertTrue("langRestrict=pt" in first && "inauthor%3A%22Jos%C3%A9+Saramago%22" in first && "key=KEY" in first, first)
        assertTrue("langRestrict" !in server.takeRequest().path)
    }

    @Test
    fun enoughPortugueseResultsNeedNoSecondSearch() = runTest {
        server.enqueue(page(*Array(12) { item("Livro $it", listOf("Ana Lima")) }))
        assertEquals(12, search.booksBy("Ana Lima").size)
        server.takeRequest()
        assertEquals(0, server.requestCount)
    }

    @Test
    fun failuresGiveNothingAndAnswersAreRemembered() = runTest {
        server.enqueue(FakeResponse().setResponseCode(503))
        server.enqueue(FakeResponse().setResponseCode(503))
        assertEquals(emptyList(), search.booksBy("Nobody Known"))
        server.enqueue(page(item("Um", listOf("Rui Zink"))))
        server.enqueue(page())
        search.booksBy("Rui Zink")
        val before = server.requestCount
        assertEquals(listOf("Um"), search.booksBy("Rui Zink").map { it.title })
        assertEquals(before, server.requestCount)
        assertEquals(emptyList(), search.booksBy(" "))
    }
}
