package com.mcosta.mybooks.data.news

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.coroutines.CancellationException

/** The news is not available: not published yet ([notPublished]), or no network and no saved copy. */
class NewsUnavailable(val notPublished: Boolean, cause: Throwable? = null) : Exception(cause)

/** Downloads the news job's files from GitHub Pages, as raw JSON. */
class NewsClient(private val http: HttpClient, private val baseUrl: String = NEWS_BASE_URL) {
    suspend fun news(): String = get("news.json")

    suspend fun events(): String = get("events.json")

    private suspend fun get(name: String): String = try {
        download(baseUrl + name) ?: throw NewsUnavailable(notPublished = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: NewsUnavailable) {
        throw e
    } catch (e: Exception) {
        throw NewsUnavailable(notPublished = false, cause = e)
    }

    /** The body as text, null on 404; a response over [MAX_BYTES] (declared or actually read) is refused. */
    private suspend fun download(url: String): String? {
        val response = http.get(url)
        if (response.status.value == 404) return null
        if (!response.status.isSuccess()) throw NewsUnavailable(notPublished = false)
        val declared = response.contentLength()
        if (declared != null && declared > MAX_BYTES) throw NewsUnavailable(notPublished = false)
        val bytes = response.bodyAsChannel().readRemaining(MAX_BYTES + 1).readByteArray()
        if (bytes.size > MAX_BYTES) throw NewsUnavailable(notPublished = false)
        return bytes.decodeToString()
    }

    private companion object {
        const val MAX_BYTES = 2L * 1024 * 1024
    }
}
