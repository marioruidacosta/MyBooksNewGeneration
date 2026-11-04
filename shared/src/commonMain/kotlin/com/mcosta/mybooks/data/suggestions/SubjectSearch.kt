package com.mcosta.mybooks.data.suggestions

import com.mcosta.mybooks.data.authors.AuthorBook
import com.mcosta.mybooks.data.lookup.getOrNull
import com.mcosta.mybooks.domain.searchKey
import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What books are about, and other books about the same thing. Empty answers when nothing is found or offline. */
interface SubjectSource {
    /** The book's subjects, most telling first, without catalogue noise or its own author. */
    suspend fun subjectsOf(isbn: String, authors: String): List<String>
    suspend fun booksOn(subject: String): List<AuthorBook>
}

/**
 * Subjects from Open Library (the edition's work), books on a subject from Google Books. Google Books ignores
 * the language filter for subjects, so these are mostly other-language editions.
 */
class SubjectSearch(
    private val http: HttpClient,
    private val apiKey: String? = null,
    private val openLibrary: String = "https://openlibrary.org",
    private val googleBooks: String = "https://www.googleapis.com",
) : SubjectSource {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun subjectsOf(isbn: String, authors: String): List<String> = quietly(emptyList()) {
        val edition = http.getOrNull("$openLibrary/isbn/$isbn.json")?.let { json.decodeFromString<Edition>(it) } ?: return@quietly emptyList()
        val work = edition.works.firstOrNull()?.key ?: return@quietly emptyList()
        val subjects = http.getOrNull("$openLibrary$work.json")?.let { json.decodeFromString<Work>(it) }?.subjects.orEmpty()
        val authorKeys = authors.split(',').map { searchKey(it.trim()) }.filter { it.isNotEmpty() }
        subjects.map { it.trim() }.filter { isTelling(it) && searchKey(it) !in authorKeys }.distinctBy { searchKey(it) }
    }

    override suspend fun booksOn(subject: String): List<AuthorBook> = quietly(emptyList()) {
        val query = "subject:\"$subject\"".encodeURLParameter(spaceToPlus = true)
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { "&key=$it" }.orEmpty()
        val body = http.getOrNull("$googleBooks/books/v1/volumes?q=$query&printType=books&maxResults=20$key") ?: return@quietly emptyList()
        json.decodeFromString<Volumes>(body).items.mapNotNull { it.volumeInfo }.filter { it.title.isNotBlank() }.map { info ->
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
    }

    private suspend fun <T> quietly(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback
    }

    @Serializable
    private data class Edition(val works: List<Ref> = emptyList())

    @Serializable
    private data class Ref(val key: String)

    @Serializable
    private data class Work(val subjects: List<String> = emptyList())

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
        /** Subjects that say nothing about what a book is like. */
        val GENERIC = setOf(
            "fiction", "ficcao", "literature", "literatura", "novel", "novels", "romance", "romances", "general", "geral",
            "english fiction", "american fiction", "french fiction", "portuguese fiction", "fiction in english",
        )

        /** Catalogue and shop labels rather than subjects. */
        val NOISE = listOf(
            "translation", "general", "new york times", "bestseller", "accessible book", "protected daisy", "in library",
            "large type", "lending library", "open library", "staff picks", "reading level", "award",
        )

        fun isTelling(subject: String): Boolean {
            val key = searchKey(subject)
            return key.length in 3..40 && ':' !in key && '=' !in key && key !in GENERIC && NOISE.none { it in key }
        }
    }
}
