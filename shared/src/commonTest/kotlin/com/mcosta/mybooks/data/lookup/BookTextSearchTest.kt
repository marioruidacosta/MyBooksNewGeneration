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
                   "industryIdentifiers":[{"type":"ISBN_10","identifier":"9722110527"},{"type":"ISBN_13","identifier":"9789722110525"}],
                   "imageLinks":{"thumbnail":"http://books.google.com/c.jpg"}}},
                  {"volumeInfo":{"title":"","authors":["Ninguém"]}},
                  {"volumeInfo":{"title":"Blindness","authors":["José Saramago","Giovanni Pontiero"],"language":"en"}}
                ]}""",
            ),
        )
        val hits = GoogleBooksTextSearch(server.httpClient(), baseUrl = server.baseUrl).search("cegueira")
        assertEquals(
            PublicHit(
                AuthorBook("Ensaio sobre a Cegueira", "José Saramago", 1995, "Caminho", "9789722110525", "https://books.google.com/c.jpg", "Uma cegueira branca.", 310),
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
                   "isbn":["123","9780140449075","9789722039994","9722039994"],"cover_i":8231856,"language":["por","eng"],"publisher":["Penguin","Porto Editora"]}]}""",
            ),
        )
        val hits = OpenLibraryTextSearch(server.httpClient(), baseUrl = server.baseUrl).search("os maias")
        // A work lists every edition's ISBN and publisher in no order: only a Portuguese edition's ISBN is kept
        // (978-972, 978-989), and no publisher, so nothing from a foreign edition is saved or used for store links.
        assertEquals(
            listOf(
                PublicHit(
                    AuthorBook("Os Maias", "Eça de Queirós", 1888, null, "9789722039994", "https://covers.openlibrary.org/b/id/8231856-M.jpg", null, null),
                    portuguese = true,
                ),
            ),
            hits,
        )
    }

    @Test
    fun openLibraryKeepsNoForeignEditionIsbn() = runTest {
        server.enqueue(
            body(
                """{"docs":[{"title":"Blindness","author_name":["José Saramago"],"isbn":["9780156007757","9789897540107"],"language":["eng"]},
                   {"title":"Ensaio sobre a Cegueira","author_name":["José Saramago"],"isbn":["9780156007757","0156007754"],"language":["por","eng"]}]}""",
            ),
        )
        val hits = OpenLibraryTextSearch(server.httpClient(), baseUrl = server.baseUrl).search("saramago")
        assertEquals(listOf(null, null), hits.map { it.book.isbn })
    }

    @Test
    fun googleIsbn10OnlyIsKept() = runTest {
        server.enqueue(
            body("""{"items":[{"volumeInfo":{"title":"A Cidade e as Serras","industryIdentifiers":[{"type":"ISBN_10","identifier":"972-20-3650-5"}]}}]}"""),
        )
        val hits = GoogleBooksTextSearch(server.httpClient(), baseUrl = server.baseUrl).search("serras")
        assertEquals("9789722036504", hits.single().book.isbn)
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
