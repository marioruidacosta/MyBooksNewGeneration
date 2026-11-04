package com.mcosta.mybooks.data.authors

import com.mcosta.mybooks.data.lookup.FakeHttpServer
import com.mcosta.mybooks.data.lookup.FakeResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WikipediaAuthorsTest {
    private val server = FakeHttpServer()
    private val authors = WikipediaAuthors(server.httpClient(), baseUrl = { lang -> "${server.baseUrl}/$lang" })

    private fun summary(type: String, title: String, description: String, extract: String, photo: String? = null) = FakeResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"type":"$type","title":"$title","description":"$description","extract":"$extract",""" +
                (photo?.let { """"thumbnail":{"source":"$it"},""" } ?: "") +
                """"content_urls":{"mobile":{"page":"https://pt.m.wikipedia.org/wiki/x"}}}""",
        )

    private val notFound = FakeResponse().setResponseCode(404).setBody("""{"type":"https://mediawiki.org/wiki/HyperSwitch/errors/not_found"}""")

    @Test
    fun portugueseWikipediaFirst() = runTest {
        server.enqueue(
            summary(
                "standard", "José Saramago", "escritor português, Nobel da Literatura de 1998 (1922-2010)",
                "José de Sousa Saramago foi um escritor português.", "https://img/js.jpg",
            ),
        )
        val info = authors.author("José Saramago")!!
        assertEquals("José Saramago", info.name)
        assertEquals("escritor português, Nobel da Literatura de 1998 (1922-2010)", info.description)
        assertEquals("José de Sousa Saramago foi um escritor português.", info.extract)
        assertEquals("https://img/js.jpg", info.photoUrl)
        assertEquals("/pt/api/rest_v1/page/summary/Jos%C3%A9_Saramago", server.takeRequest().path)
    }

    @Test
    fun englishWhenPortugueseHasNoPage() = runTest {
        server.enqueue(notFound)
        server.enqueue(summary("standard", "Percival Everett", "American writer", "Percival Everett is an American writer and academic."))
        assertEquals("American writer", authors.author("Percival Everett")?.description)
        assertEquals("/pt/api/rest_v1/page/summary/Percival_Everett", server.takeRequest().path)
        assertEquals("/en/api/rest_v1/page/summary/Percival_Everett", server.takeRequest().path)
    }

    @Test
    fun disambiguationAndNonWritersAreNotTheAuthor() = runTest {
        server.enqueue(summary("disambiguation", "Ronaldo", "página de desambiguação da Wikimedia", "Ronaldo pode referir-se a:"))
        server.enqueue(summary("standard", "Ronaldo", "Brazilian footballer", "Ronaldo is a Brazilian former footballer."))
        assertNull(authors.author("Ronaldo"))
    }

    @Test
    fun nothingAnywhereOrNoNetwork() = runTest {
        server.enqueue(notFound)
        server.enqueue(notFound)
        assertNull(authors.author("Jade Khoo"))
        server.enqueue(FakeResponse().setResponseCode(500))
        server.enqueue(FakeResponse().setResponseCode(500))
        assertNull(authors.author("Someone Else"))
    }

    @Test
    fun answersAreRememberedForTheSession() = runTest {
        server.enqueue(summary("standard", "Valeria Luiselli", "escritora mexicana", "Valeria Luiselli é uma escritora mexicana."))
        authors.author("Valeria Luiselli")
        assertEquals("escritora mexicana", authors.author("Valeria Luiselli")?.description)
        server.takeRequest()
        assertEquals(0, server.requestCount)
    }

    @Test
    fun blankNamesAskNothing() = runTest {
        assertNull(authors.author("  "))
        assertEquals(0, server.requestCount)
    }
}
