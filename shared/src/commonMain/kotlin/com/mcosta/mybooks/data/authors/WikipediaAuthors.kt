package com.mcosta.mybooks.data.authors

import com.mcosta.mybooks.data.lookup.getOrNull
import com.mcosta.mybooks.domain.searchKey
import io.ktor.client.HttpClient
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** An author as Wikipedia describes them. */
data class AuthorInfo(
    val name: String,
    /** "escritor português, Nobel da Literatura de 1998 (1922-2010)". */
    val description: String?,
    val extract: String,
    val photoUrl: String?,
    /** The Wikipedia page, credited under the text. */
    val pageUrl: String?,
)

/** Finds an author's short biography; null when none is found (or there's no network). */
fun interface AuthorInfoSource {
    suspend fun author(name: String): AuthorInfo?
}

/**
 * Wikipedia page summaries: Portuguese Wikipedia first (most of the library is Portuguese editions), then
 * English. A page counts only when it describes a writer (or translator, poet, …), so a footballer or a
 * disambiguation page with the same name isn't shown. Answers, found or not, are kept for the session.
 */
class WikipediaAuthors(
    private val http: HttpClient,
    private val baseUrl: (lang: String) -> String = { lang -> "https://$lang.wikipedia.org" },
) : AuthorInfoSource {
    private val json = Json { ignoreUnknownKeys = true }
    private val known = mutableMapOf<String, AuthorInfo?>()
    private val lock = Mutex()

    override suspend fun author(name: String): AuthorInfo? {
        val key = searchKey(name.trim())
        if (key.isEmpty()) return null
        lock.withLock { if (key in known) return known[key] }
        var found: AuthorInfo? = null
        for (lang in LANGUAGES) {
            found = summary(lang, name.trim())
            if (found != null) break
        }
        lock.withLock { known[key] = found }
        return found
    }

    private suspend fun summary(lang: String, name: String): AuthorInfo? = try {
        val title = name.replace(' ', '_').encodeURLPathPart()
        http.getOrNull("${baseUrl(lang)}/api/rest_v1/page/summary/$title")
            ?.let { json.decodeFromString<Summary>(it) }
            ?.takeIf { it.type == "standard" && it.extract.isNotBlank() && isWriter(it) }
            ?.let { AuthorInfo(it.title, it.description, it.extract, it.thumbnail?.source, it.content_urls?.mobile?.page) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun isWriter(s: Summary): Boolean {
        val text = searchKey("${s.description.orEmpty()} ${s.extract.substringBefore(". ")}")
        return WRITER_WORDS.any { it in text }
    }

    @Suppress("PropertyName")
    @Serializable
    private data class Summary(
        val type: String = "",
        val title: String = "",
        val description: String? = null,
        val extract: String = "",
        val thumbnail: Image? = null,
        val content_urls: Urls? = null,
    )

    @Serializable
    private data class Image(val source: String)

    @Serializable
    private data class Urls(val mobile: Page? = null)

    @Serializable
    private data class Page(val page: String? = null)

    private companion object {
        val LANGUAGES = listOf("pt", "en")

        /** Word stems (accent-free, lower case) of the people whose books end up on a shelf. */
        val WRITER_WORDS = listOf(
            "escritor", "autor", "poet", "romancista", "novelista", "ensaista", "dramaturg", "jornalista", "contista",
            "cronista", "filosof", "historiador", "tradutor", "ilustrador", "biografo", "argumentista", "cartoonista",
            "writer", "author", "novelist", "essayist", "playwright", "journalist", "philosopher", "historian",
            "translator", "illustrator", "biographer", "screenwriter", "cartoonist",
        )
    }
}
