package com.mcosta.mybooks.data.suggestions

import com.mcosta.mybooks.data.lookup.FakeHttpServer
import com.mcosta.mybooks.data.lookup.FakeResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubjectSearchTest {
    private val server = FakeHttpServer()
    private val search = SubjectSearch(server.httpClient(), apiKey = "KEY", openLibrary = server.baseUrl, googleBooks = server.baseUrl)

    private fun json(body: String) = FakeResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun subjectsOfABookWithoutTheNoise() = runTest {
        server.enqueue(json("""{"title":"Solaris","works":[{"key":"/works/OL109524W"}]}"""))
        server.enqueue(
            json(
                """{"subjects":["Translations into English","English fiction","Science fiction","Fiction, science fiction, general",""" +
                    """"Life on other planets","Fiction","nyt:paperback=2011","Stanisław Lem","New York Times bestseller","Memory"]}""",
            ),
        )
        assertEquals(listOf("Science fiction", "Life on other planets", "Memory"), search.subjectsOf("9789726083962", authors = "Stanisław Lem"))
        assertEquals("/isbn/9789726083962.json", server.takeRequest().path)
        assertEquals("/works/OL109524W.json", server.takeRequest().path)
    }

    @Test
    fun unknownBooksHaveNoSubjects() = runTest {
        server.enqueue(FakeResponse().setResponseCode(404))
        assertEquals(emptyList(), search.subjectsOf("9789895894475", authors = "X"))
        server.enqueue(FakeResponse().setResponseCode(500))
        assertEquals(emptyList(), search.subjectsOf("9789722378482", authors = "X"))
    }

    @Test
    fun booksOnASubject() = runTest {
        server.enqueue(
            json(
                """{"items":[{"volumeInfo":{"title":"Perelandra","authors":["C.S. Lewis"],"publishedDate":"1943",""" +
                    """"industryIdentifiers":[{"type":"ISBN_13","identifier":"9780743234917"}],"imageLinks":{"thumbnail":"http://b/p.jpg"}}},""" +
                    """{"volumeInfo":{"title":"Contact","authors":["Carl Sagan"]}},{"volumeInfo":{"title":""}}]}""",
            ),
        )
        val books = search.booksOn("Life on other planets")
        assertEquals(listOf("Perelandra", "Contact"), books.map { it.title })
        assertEquals("https://b/p.jpg", books[0].coverUrl)
        assertEquals("9780743234917", books[0].isbn)
        assertEquals(1943, books[0].year)
        val path = server.takeRequest().path
        assertTrue("subject%3A%22Life+on+other+planets%22" in path && "key=KEY" in path, path)
    }
}
