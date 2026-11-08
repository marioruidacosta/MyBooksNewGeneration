package com.mcosta.mybooks.data.lookup

import com.mcosta.mybooks.data.authors.AuthorBook
import com.mcosta.mybooks.domain.Isbn
import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A book a public catalogue found for a search, and whether it is a Portuguese edition (those are shown first). */
data class PublicHit(val book: AuthorBook, val portuguese: Boolean)

/** A public catalogue searched by text (title or author). Throws when the catalogue can't be reached or read. */
fun interface BookTextSource {
    suspend fun search(query: String): List<PublicHit>
}

private val json = Json { ignoreUnknownKeys = true }

private fun encoded(query: String) = query.trim().encodeURLParameter(spaceToPlus = true)

/** Google Books searched by text (books only, up to 20). */
class GoogleBooksTextSearch(
    private val http: HttpClient,
    private val apiKey: String? = null,
    private val baseUrl: String = "https://www.googleapis.com",
) : BookTextSource {
    override suspend fun search(query: String): List<PublicHit> {
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { "&key=$it" }.orEmpty()
        val body = http.getOrNull("$baseUrl/books/v1/volumes?q=${encoded(query)}&printType=books&maxResults=20$key") ?: return emptyList()
        return json.decodeFromString<Volumes>(body).items.mapNotNull { it.volumeInfo }.filter { it.title.isNotBlank() }.map { info ->
            PublicHit(
                AuthorBook(
                    title = info.title.trim(),
                    authors = info.authors.joinToString(", "),
                    year = yearFrom(info.publishedDate),
                    publisher = info.publisher,
                    isbn = info.industryIdentifiers.firstOrNull { it.type == "ISBN_13" }?.identifier,
                    coverUrl = httpsUrl(info.imageLinks?.thumbnail ?: info.imageLinks?.smallThumbnail),
                    description = info.description,
                    pageCount = info.pageCount,
                ),
                portuguese = info.language == "pt",
            )
        }
    }

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
        val language: String? = null,
        val industryIdentifiers: List<Identifier> = emptyList(),
        val imageLinks: Images? = null,
    )

    @Serializable
    private data class Identifier(val type: String = "", val identifier: String = "")

    @Serializable
    private data class Images(val thumbnail: String? = null, val smallThumbnail: String? = null)
}

/** Open Library searched by text (up to 20). */
class OpenLibraryTextSearch(
    private val http: HttpClient,
    private val baseUrl: String = "https://openlibrary.org",
) : BookTextSource {
    override suspend fun search(query: String): List<PublicHit> {
        val fields = "title,author_name,first_publish_year,isbn,cover_i,language,publisher"
        val body = http.getOrNull("$baseUrl/search.json?q=${encoded(query)}&limit=20&fields=$fields") ?: return emptyList()
        return json.decodeFromString<Results>(body).docs.filter { it.title.isNotBlank() }.map { doc ->
            PublicHit(
                AuthorBook(
                    title = doc.title.trim(),
                    authors = doc.author_name.joinToString(", "),
                    year = doc.first_publish_year,
                    publisher = doc.publisher.firstOrNull(),
                    isbn = doc.isbn.firstNotNullOfOrNull { Isbn.normalize(it) },
                    coverUrl = doc.cover_i?.let { "https://covers.openlibrary.org/b/id/$it-M.jpg" },
                    description = null,
                    pageCount = null,
                ),
                portuguese = "por" in doc.language,
            )
        }
    }

    @Serializable
    private data class Results(val docs: List<Doc> = emptyList())

    @Suppress("PropertyName")
    @Serializable
    private data class Doc(
        val title: String = "",
        val author_name: List<String> = emptyList(),
        val first_publish_year: Int? = null,
        val isbn: List<String> = emptyList(),
        val cover_i: Long? = null,
        val language: List<String> = emptyList(),
        val publisher: List<String> = emptyList(),
    )
}
