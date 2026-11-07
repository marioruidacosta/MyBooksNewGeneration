package com.mcosta.mybooks.data.ebooks

import com.mcosta.mybooks.domain.personName
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.Source

/** A free public-domain book on Project Gutenberg, with its EPUB. */
data class Classic(
    val id: Int,
    val title: String,
    val authors: List<String>,
    val languages: List<String>,
    val epubUrl: String,
    val coverUrl: String?,
)

/** Free classics: searching, and downloading an EPUB. */
interface ClassicsSource {
    suspend fun search(query: String): List<Classic>
    suspend fun download(classic: Classic, into: suspend (Source) -> Unit)
}

/** Project Gutenberg (through Gutendex) couldn't be reached, answered with an error, or took too long. */
class ClassicsUnavailable(cause: Throwable? = null) : Exception("Project Gutenberg couldn't be reached", cause)

/**
 * Searches Project Gutenberg's free books through the Gutendex API and downloads their EPUBs. Searches can be slow:
 * they wait up to [timeoutMillis] (30 s), downloads four times as long.
 */
class ClassicsClient(
    private val http: HttpClient,
    private val base: String = "https://gutendex.com/books/",
    private val timeoutMillis: Long = 30_000,
) : ClassicsSource {
    private val json = Json { ignoreUnknownKeys = true }

    /** Books matching [query] (title or author words) that have an EPUB, in Gutendex's order (most downloaded first). */
    override suspend fun search(query: String): List<Classic> = unavailableOnFailure {
        val response = http.get(base) {
            parameter("search", query.trim())
            timeout { requestTimeoutMillis = timeoutMillis }
        }
        if (!response.status.isSuccess()) throw ClassicsUnavailable()
        json.decodeFromString<Page>(response.bodyAsText()).results.mapNotNull { it.toClassic() }
    }

    /** Downloads [classic]'s EPUB and hands it to [into]. EPUBs from Gutenberg are a few MB, so it is read whole. */
    override suspend fun download(classic: Classic, into: suspend (Source) -> Unit) {
        val bytes = unavailableOnFailure {
            val response = http.get(classic.epubUrl) { timeout { requestTimeoutMillis = timeoutMillis * 4 } }
            if (!response.status.isSuccess()) throw ClassicsUnavailable()
            response.bodyAsBytes()
        }
        into(Buffer().write(bytes))
    }

    private inline fun <T> unavailableOnFailure(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ClassicsUnavailable) {
        throw e
    } catch (e: Exception) {
        throw ClassicsUnavailable(e)
    }

    @Serializable
    private data class Page(val results: List<Result> = emptyList())

    @Serializable
    private data class Person(val name: String = "")

    @Serializable
    private data class Result(
        val id: Int,
        val title: String = "",
        val authors: List<Person> = emptyList(),
        val languages: List<String> = emptyList(),
        val formats: Map<String, String> = emptyMap(),
    ) {
        fun toClassic(): Classic? {
            val epub = formats["application/epub+zip"] ?: return null
            return Classic(id, title.trim(), authors.map { personName(it.name) }.filter { it.isNotEmpty() }, languages, epub, formats["image/jpeg"])
        }
    }
}
