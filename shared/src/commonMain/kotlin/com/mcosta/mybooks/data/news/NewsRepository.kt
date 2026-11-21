package com.mcosta.mybooks.data.news

import com.mcosta.mybooks.data.releases.Cached
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Book news, saved on the phone. The news is downloaded again when the saved copy is older than 30 minutes (or when
 * asked to); a failed download never touches the saved copy, which is shown instead (as [Cached.offline]).
 *
 * Loads are serialised: Home and Book news share one repository and both load at start-up, and two downloads must not
 * write the same files at once. A caller that waited for a download to finish gets that fresh copy, even when it asked
 * to force a refresh, rather than downloading again.
 */
class NewsRepository(
    private val client: NewsClient,
    private val fs: FileSystem,
    private val dir: Path,
    private val clock: () -> Instant = { Clock.System.now() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val newsGate = Gate()
    private val eventsGate = Gate()

    /** The feed, newest first. Throws [NewsUnavailable] only when the download fails and nothing is saved. */
    suspend fun news(force: Boolean = false): Cached<NewsFeed> =
        load(newsGate, "news", NEWS_MAX_AGE, force, { client.news() }) { text -> parseFeed(text).clean(clock()) }

    /** The recurring events, or none when they cannot be loaded (the calendar is a side feature). */
    suspend fun events(): List<BookEvent> = try {
        load(eventsGate, "events", EVENTS_MAX_AGE, false, { client.events() }) { text ->
            json.decodeFromString<RawEvents>(text).events.decodeEach<BookEvent>(json)
                .filter { it.month in 1..12 && isHttps(it.url) && it.name.isNotBlank() }
                .distinctBy { it.name }
        }.value
    } catch (e: NewsUnavailable) {
        emptyList()
    }

    /** Reading, parsing and saving all run on [io], off the main thread. */
    private suspend fun <T : Any> load(
        gate: Gate,
        name: String,
        maxAge: Duration,
        force: Boolean,
        download: suspend () -> String,
        parse: (String) -> T,
    ): Cached<T> {
        val seen = gate.downloads
        return gate.lock.withLock {
            // A download finished while this call waited: its copy is as fresh as a forced refresh would give.
            loadLocked(gate, name, maxAge, force && gate.downloads == seen, download, parse)
        }
    }

    private suspend fun <T : Any> loadLocked(
        gate: Gate,
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
        // A saved date in the future (the clock was set back) counts as stale.
        if (saved != null && !force && (now - saved.savedAt) in Duration.ZERO..<maxAge) return saved
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
        gate.downloads++
        return Cached(value, now, offline = false)
    }

    /** The file must be a JSON object with lists; each story and source is then read on its own, bad ones dropped. */
    private fun parseFeed(text: String): NewsFeed {
        val raw = json.decodeFromString<RawFeed>(text)
        return NewsFeed(raw.generatedAt, raw.sources.decodeEach(json), raw.items.decodeEach(json))
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
            // Each file is written beside its final name, then moved over it; the date goes last, so a copy
            // without its date (an interrupted save) is never used.
            writeAtomically(dir / "$name.json", text)
            writeAtomically(dir / "$name.at", at.toEpochMilliseconds().toString())
        } catch (e: IOException) {
            Unit
        }
    }

    private fun writeAtomically(path: Path, text: String) {
        val temp = path.parent!! / (path.name + ".tmp")
        fs.write(temp) { writeUtf8(text) }
        fs.atomicMove(temp, path)
    }

    /** One lock per saved file, and how many downloads it has seen (only read and changed under the lock, or before waiting for it). */
    private class Gate {
        val lock = Mutex()
        var downloads = 0
    }

    private companion object {
        val NEWS_MAX_AGE = 30.minutes
        val EVENTS_MAX_AGE = 24.hours
    }
}

private fun isHttps(url: String) = url.startsWith("https://", ignoreCase = true)

@Serializable
private class RawFeed(val generatedAt: Long = 0, val sources: List<JsonElement> = emptyList(), val items: List<JsonElement> = emptyList())

@Serializable
private class RawEvents(val events: List<JsonElement> = emptyList())

/** Decodes each element on its own; the ones that do not fit [T] are dropped. */
private inline fun <reified T> List<JsonElement>.decodeEach(json: Json): List<T> = mapNotNull {
    try {
        json.decodeFromJsonElement<T>(it)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}

private val LANGUAGES = setOf("pt", "en")
private const val MAX_ITEMS = 300
private const val MAX_TITLE = 200
private const val MAX_SUMMARY = 280

/** [text] cut at a word boundary to at most [max] characters, ending with an ellipsis. */
private fun cap(text: String, max: Int): String {
    if (text.length <= max) return text
    val cut = text.take(max - 1)
    val space = cut.lastIndexOf(' ')
    return (if (space > max / 2) cut.take(space) else cut).trimEnd() + "…"
}

/**
 * Only `https:` links are kept (a story without one is dropped, an image without one is cleared); a story with a blank
 * title or a language other than `pt`/`en` is dropped too, as on the website; one story per id,
 * newest first, at most [MAX_ITEMS]; texts are capped again and dates more than a day ahead of [now] are pulled back.
 */
private fun NewsFeed.clean(now: Instant): NewsFeed {
    val latest = (now + 1.days).toEpochMilliseconds()
    return copy(
        items = items.filter { isHttps(it.url) && it.title.isNotBlank() && it.lang in LANGUAGES }
            .map {
                it.copy(
                    title = cap(it.title, MAX_TITLE),
                    summary = cap(it.summary, MAX_SUMMARY),
                    published = minOf(it.published, latest),
                    image = it.image?.takeIf(::isHttps),
                )
            }
            .sortedByDescending { it.published }
            .distinctBy { it.id }
            .take(MAX_ITEMS),
    )
}
