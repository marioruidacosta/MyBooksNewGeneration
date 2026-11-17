package com.mcosta.mybooks.data.news

import com.mcosta.mybooks.data.releases.Cached
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Book news, saved on the phone. The news is downloaded again when the saved copy is older than 30 minutes (or when
 * asked to); a failed download never touches the saved copy, which is shown instead (as [Cached.offline]).
 */
class NewsRepository(
    private val client: NewsClient,
    private val fs: FileSystem,
    private val dir: Path,
    private val clock: () -> Instant = { Clock.System.now() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The feed, newest first. Throws [NewsUnavailable] only when the download fails and nothing is saved. */
    suspend fun news(force: Boolean = false): Cached<NewsFeed> =
        load("news", NEWS_MAX_AGE, force, { client.news() }) { text -> json.decodeFromString<NewsFeed>(text).clean() }

    /** The recurring events, or none when they cannot be loaded (the calendar is a side feature). */
    suspend fun events(): List<BookEvent> = try {
        load("events", EVENTS_MAX_AGE, false, { client.events() }) { text ->
            json.decodeFromString<EventsJson>(text).events.filter { it.month in 1..12 && isHttps(it.url) }
        }.value
    } catch (e: NewsUnavailable) {
        emptyList()
    }

    /** Reading, parsing and saving all run on [io], off the main thread. */
    private suspend fun <T : Any> load(
        name: String,
        maxAge: Duration,
        force: Boolean,
        download: suspend () -> String,
        parse: (String) -> T,
    ): Cached<T> {
        val now = clock()
        val saved = withContext(io) {
            read(name)?.let { (text, at) -> parseOrNull { parse(text) }?.let { Cached(it, at, offline = false) } }
        }
        if (saved != null && !force && now - saved.savedAt < maxAge) return saved
        val text = try {
            download()
        } catch (e: NewsUnavailable) {
            if (saved != null) return saved.copy(offline = true)
            throw e
        }
        // Not our JSON (e.g. a Wi-Fi login page answering instead): treat it like being offline.
        val value = withContext(io) { parseOrNull { parse(text) } }
            ?: return saved?.copy(offline = true) ?: throw NewsUnavailable(notPublished = false)
        withContext(io) { write(name, text, now) }
        return Cached(value, now, offline = false)
    }

    private fun <T> parseOrNull(block: () -> T): T? = try {
        block()
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** The saved copy and when it was downloaded; null when there is none or it cannot be read. */
    private fun read(name: String): Pair<String, Instant>? = try {
        val file = dir / "$name.json"
        val stamp = dir / "$name.at"
        if (!fs.exists(file) || !fs.exists(stamp)) {
            null
        } else {
            fs.read(stamp) { readUtf8() }.trim().toLongOrNull()?.let { at -> fs.read(file) { readUtf8() } to Instant.fromEpochMilliseconds(at) }
        }
    } catch (e: IOException) {
        null
    }

    /** Saving is best effort: a full disk only means the news is downloaded again next time. */
    private fun write(name: String, text: String, at: Instant) {
        try {
            fs.createDirectories(dir)
            fs.write(dir / "$name.json") { writeUtf8(text) }
            fs.write(dir / "$name.at") { writeUtf8(at.toEpochMilliseconds().toString()) }
        } catch (e: IOException) {
            Unit
        }
    }

    private companion object {
        val NEWS_MAX_AGE = 30.minutes
        val EVENTS_MAX_AGE = 24.hours
    }
}

private fun isHttps(url: String) = url.startsWith("https://", ignoreCase = true)

/** Only `https:` links are kept (a story without one is dropped, an image without one is cleared); newest first. */
private fun NewsFeed.clean(): NewsFeed = copy(
    items = items.filter { isHttps(it.url) }
        .map { if (it.image != null && !isHttps(it.image)) it.copy(image = null) else it }
        .sortedByDescending { it.published },
)
