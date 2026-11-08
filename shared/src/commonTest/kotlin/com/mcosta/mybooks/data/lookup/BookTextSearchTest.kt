package com.mcosta.mybooks.data.lookup

import com.mcosta.mybooks.data.authors.AuthorBook
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class BookTextSearchTest {
    private val server = FakeHttpServer()

    private fun body(text: String) = FakeResponse().setHeader("Content-Type", "application/json").setBody(text)

    @Test
    fun googleResultsMapped() = runTest {
        server.enqueue(
            body(
                """{"items":[
                  {"volumeInfo":{"title":"Ensaio sobre a Cegueira","authors":["José Saramago"],"publisher":"Caminho",
                   "publishedDate":"1995-10-01","description":"Uma cegueira branca.","pageCount":310,"language":"pt",
                   "industryIdentifiers":[{"type":"ISBN_10","identifier":"9722110527"},{"type":"ISBN_13","identifier":"9789722110520"}],
                   "imageLinks":{"thumbnail":"http://books.google.com/c.jpg"}}},
                  {"volumeInfo":{"title":"","authors":["Ninguém"]}},
                  {"volumeInfo":{"title":"Blindness","authors":["José Saramago","Giovanni Pontiero"],"language":"en"}}
                ]}""",
            ),
        )
        val hits = GoogleBooksTextSearch(server.httpClient(), baseUrl = server.baseUrl).search("cegueira")
        assertEquals(
            PublicHit(
                AuthorBook("Ensaio sobre a Cegueira", "José Saramago", 1995, "Caminho", "9789722110520", "https://books.google.com/c.jpg", "Uma cegueira branca.", 310),
                portuguese = true,
            ),
            hits[0],
        )
        assertEquals(2, hits.size)
        assertEquals("José Saramago, Giovanni Pontiero", hits[1].book.authors)
        assertFalse(hits[1].portuguese)
    }

    @Test
    fun openLibraryResultsMapped() = runTest {
        server.enqueue(
            body(
                """{"docs":[{"title":"Os Maias","author_name":["Eça de Queirós"],"first_publish_year":1888,
                   "isbn":["123","9789722039994","9722039994"],"cover_i":8231856,"language":["por","eng"],"publisher":["Porto Editora","Livros do Brasil"]}]}""",
            ),
        )
        val hits = OpenLibraryTextSearch(server.httpClient(), baseUrl = server.baseUrl).search("os maias")
        assertEquals(
            listOf(
                PublicHit(
                    AuthorBook("Os Maias", "Eça de Queirós", 1888, "Porto Editora", "9789722039994", "https://covers.openlibrary.org/b/id/8231856-M.jpg", null, null),
                    portuguese = true,
                ),
            ),
            hits,
        )
    }

    /** Review focus: "&", quotes, accents and "#" reach both catalogues encoded. */
    @Test
    fun queryIsEncoded() = runTest {
        server.enqueue(body("{}"))
        server.enqueue(body("{}"))
        server.enqueue(body("{}"))
        GoogleBooksTextSearch(server.httpClient(), baseUrl = server.baseUrl).search("Eça & \"Cia\" #1")
        assertEquals("/books/v1/volumes?q=E%C3%A7a+%26+%22Cia%22+%231&printType=books&maxResults=20", server.takeRequest().path)
        GoogleBooksTextSearch(server.httpClient(), apiKey = "k", baseUrl = server.baseUrl).search("maias")
        assertTrue(server.takeRequest().path.endsWith("&key=k"))
        OpenLibraryTextSearch(server.httpClient(), baseUrl = server.baseUrl).search("Eça & \"Cia\" #1")
        assertEquals(
            "/search.json?q=E%C3%A7a+%26+%22Cia%22+%231&limit=20&fields=title,author_name,first_publish_year,isbn,cover_i,language,publisher",
            server.takeRequest().path,
        )
    }

    @Test
    fun httpErrorThrows() = runTest {
        server.enqueue(FakeResponse().setResponseCode(500))
        assertFailsWith<Exception> { GoogleBooksTextSearch(server.httpClient(), baseUrl = server.baseUrl).search("x") }
        server.enqueue(FakeResponse().setResponseCode(500))
        assertFailsWith<Exception> { OpenLibraryTextSearch(server.httpClient(), baseUrl = server.baseUrl).search("x") }
    }
}
