package com.mcosta.mybooks.data.authors

import com.mcosta.mybooks.data.lookup.getOrNull
import com.mcosta.mybooks.domain.searchKey
import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A book by an author, as a catalogue lists it (it may not be in the library). */
data class AuthorBook(
    val title: String,
    val authors: String,
    val year: Int?,
    val publisher: String?,
    val isbn: String?,
    val coverUrl: String?,
    val description: String?,
    val pageCount: Int?,
)

/** Finds books by an author; empty when none are found (or there's no network). */
fun interface AuthorBooksSource {
    suspend fun booksBy(author: String): List<AuthorBook>
}

/**
 * Google Books searched by author: Portuguese editions first (most of the library is Portuguese), then any
 * language when there are few. Only books listing that exact author (ignoring case and accents) count, so books
 * about them don't; editions of the same title count once. Answers are kept for the session.
 */
class GoogleBooksAuthorSearch(
    private val http: HttpClient,
    private val apiKey: String? = null,
    private val baseUrl: String = "https://www.googleapis.com",
) : AuthorBooksSource {
    private val json = Json { ignoreUnknownKeys = true }
    private val known = mutableMapOf<String, List<AuthorBook>>()
    private val lock = Mutex()

    override suspend fun booksBy(author: String): List<AuthorBook> {
        val name = author.trim()
        val key = searchKey(name)
        if (key.isEmpty()) return emptyList()
        lock.withLock { known[key]?.let { return it } }
        val books = mutableListOf<AuthorBook>()
        fun add(found: List<AuthorBook>) {
            for (book in found) if (books.none { titleKey(it.title) == titleKey(book.title) } && books.size < MAX_BOOKS) books += book
        }
        add(search(name, key, portuguese = true))
        if (books.size < ENOUGH) add(search(name, key, portuguese = false))
        lock.withLock { known[key] = books }
        return books
    }

    private suspend fun search(name: String, key: String, portuguese: Boolean): List<AuthorBook> = try {
        val query = "inauthor:\"$name\"".encodeURLParameter(spaceToPlus = true)
        val keyParam = apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { "&key=$it" }.orEmpty()
        val language = if (portuguese) "&langRestrict=pt" else ""
        val body = http.getOrNull("$baseUrl/books/v1/volumes?q=$query&printType=books&maxResults=40$language$keyParam")
        body?.let { json.decodeFromString<Volumes>(it) }?.items.orEmpty().mapNotNull { it.volumeInfo }
            .filter { info -> info.authors.any { searchKey(it) == key } && info.title.isNotBlank() }
            .map { info ->
                AuthorBook(
                    title = info.title,
                    authors = info.authors.joinToString(", "),
                    year = info.publishedDate?.take(4)?.toIntOrNull(),
                    publisher = info.publisher,
                    isbn = info.industryIdentifiers.firstOrNull { it.type == "ISBN_13" }?.identifier,
                    coverUrl = (info.imageLinks?.thumbnail ?: info.imageLinks?.smallThumbnail)?.replaceFirst("http://", "https://"),
                    description = info.description,
                    pageCount = info.pageCount,
                )
            }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /** "Ensaio sobre a Cegueira: romance" and "Ensaio Sobre a Cegueira" are the same book. */
    private fun titleKey(title: String) = searchKey(title.substringBefore(':').trim())

    @Serializable
    private data class Volumes(val items: List<Item> = emptyList())

    @Serializable
    private data class Item(val volumeInfo: VolumeInfo? = null)

    @Serializable
    private data class VolumeInfo(
        val title: String = "",
        val authors: List<String> = emptyList(),
        val publisher: String? = null,
        val publishedDate: String? = null,
        val description: String? = null,
        val pageCount: Int? = null,
        val industryIdentifiers: List<Identifier> = emptyList(),
        val imageLinks: Images? = null,
    )

    @Serializable
    private data class Identifier(val type: String = "", val identifier: String = "")

    @Serializable
    private data class Images(val thumbnail: String? = null, val smallThumbnail: String? = null)

    private companion object {
        /** Fewer Portuguese books than this: other languages fill the row. */
        const val ENOUGH = 8
        const val MAX_BOOKS = 15
    }
}
