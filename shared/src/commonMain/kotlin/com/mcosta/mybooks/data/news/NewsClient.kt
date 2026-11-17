package com.mcosta.mybooks.data.news

import com.mcosta.mybooks.data.lookup.getOrNull
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException

/** The news is not available: not published yet ([notPublished]), or no network and no saved copy. */
class NewsUnavailable(val notPublished: Boolean, cause: Throwable? = null) : Exception(cause)

/** Downloads the news job's files from GitHub Pages, as raw JSON. */
class NewsClient(private val http: HttpClient, private val baseUrl: String = NEWS_BASE_URL) {
    suspend fun news(): String = get("news.json")

    suspend fun events(): String = get("events.json")

    private suspend fun get(name: String): String = try {
        http.getOrNull(baseUrl + name) ?: throw NewsUnavailable(notPublished = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: NewsUnavailable) {
        throw e
    } catch (e: Exception) {
        throw NewsUnavailable(notPublished = false, cause = e)
    }
}
