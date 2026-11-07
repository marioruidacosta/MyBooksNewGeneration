package com.mcosta.mybooks.data.ebooks

import com.mcosta.mybooks.testing.fixture
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import okio.buffer
import okio.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClassicsClientTest {
    private var asked = mutableListOf<String>()
    private var status = HttpStatusCode.OK
    private var slowMillis = 0L
    private var offline = false
    private val epub = ByteArray(70_000) { (it % 256).toByte() }
    private val engine = MockEngine { request ->
        asked += request.url.toString()
        if (offline) throw IOException("no network")
        if (slowMillis > 0) delay(slowMillis)
        when {
            request.url.host == "gutendex.test" -> respond(fixture("gutendex_search.json"), status, headersOf("Content-Type", "application/json"))
            request.url.encodedPath.endsWith(".epub3.images") -> respond(epub, status, headersOf("Content-Type", "application/epub+zip"))
            else -> respond("", HttpStatusCode.NotFound)
        }
    }
    private val client = ClassicsClient(
        HttpClient(engine) { expectSuccess = false; install(HttpTimeout) },
        base = "https://gutendex.test/books/",
        timeoutMillis = 300,
    )

    @Test
    fun searchParsesBooks() = runTest {
        val maias = client.search("maias").first()
        assertEquals(
            Classic(
                18220, "Os Maias: episodios da vida romantica", listOf("Eça de Queirós"), listOf("pt"),
                "https://www.gutenberg.org/ebooks/18220.epub3.images", "https://www.gutenberg.org/cache/epub/18220/pg18220.cover.medium.jpg",
            ),
            maias,
        )
    }

    @Test
    fun authorsAreFirstNameFirst() = runTest {
        assertEquals(listOf("Eça de Queirós", "Ramalho Ortigão"), client.search("cidade")[1].authors)
    }

    /** Review focus: only books with an EPUB are offered. */
    @Test
    fun booksWithoutEpubAreLeftOut() = runTest {
        assertEquals(listOf(18220, 27637), client.search("x").map { it.id })
    }

    @Test
    fun queryIsSentAsOneParameter() = runTest {
        client.search("os maias")
        val url = io.ktor.http.Url(asked.single())
        assertEquals("os maias", url.parameters["search"])
        assertEquals("/books/", url.encodedPath)
    }

    @Test
    fun serverErrorOfflineAndTimeoutThrowUnavailable() = runTest {
        status = HttpStatusCode.InternalServerError
        assertFailsWith<ClassicsUnavailable> { client.search("a") }
        status = HttpStatusCode.OK
        offline = true
        assertFailsWith<ClassicsUnavailable> { client.search("a") }
        offline = false
        slowMillis = 3_000
        assertFailsWith<ClassicsUnavailable> { client.search("a") }
    }

    @Test
    fun downloadStreams() = runTest {
        val classic = client.search("maias").first()
        var got = ByteArray(0)
        client.download(classic) { source -> got = source.buffer().use { it.readByteArray() } }
        assertTrue(epub.contentEquals(got))
    }

    @Test
    fun failedDownloadThrowsUnavailable() = runTest {
        val classic = client.search("maias").first()
        status = HttpStatusCode.NotFound
        assertFailsWith<ClassicsUnavailable> { client.download(classic) { } }
    }
}
