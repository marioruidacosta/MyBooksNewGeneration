package com.mcosta.mybooks.data.books

import com.mcosta.mybooks.data.lookup.getOrNull
import com.mcosta.mybooks.domain.authorKey
import com.mcosta.mybooks.domain.searchKey
import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What Wikidata knows about a book's work, shown under the book's own details. */
data class BookFacts(
    /** The year the work first came out ("1995"). */
    val firstPublished: String?,
    /** Its title in the language it was written in, and that language ("português"). */
    val originalTitle: String?,
    val originalLanguage: String?,
    val genres: List<String>,
    val awards: List<String>,
    /** The works before and after it ("Seeing"). */
    val follows: String?,
    val followedBy: String?,
    /** The Wikidata page, credited under the facts. */
    val pageUrl: String,
)

/** Finds what is known about the work behind a book; null when nothing matches (or there's no network). */
fun interface BookFactsSource {
    suspend fun facts(title: String, authors: String): BookFacts?
}

/**
 * Wikidata, as the website's wikidataFacts: the work called [title] whose author (P50) is the book's first author, then
 * its first publication (P577), language (P407), genres (P136), awards (P166) and the works before and after it
 * (P155, P156). Labels in Portuguese first, then English. Answers, found or not, are kept for the session.
 */
class WikidataFacts(
    private val http: HttpClient,
    private val baseUrl: String = "https://www.wikidata.org",
) : BookFactsSource {
    private val json = Json { ignoreUnknownKeys = true }
    private val known = mutableMapOf<String, BookFacts?>()
    private val lock = Mutex()

    override suspend fun facts(title: String, authors: String): BookFacts? {
        val name = title.trim()
        val author = authorKey(authors.substringBefore(',').trim())
        if (name.isEmpty() || author.isEmpty()) return null
        val key = "${searchKey(name)}|$author"
        lock.withLock { if (key in known) return known[key] }
        val found = try {
            find(name, author)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        lock.withLock { known[key] = found }
        return found
    }

    private suspend fun find(title: String, author: String): BookFacts? {
        val work = findWork(title, author) ?: return null
        val genres = work.refs("P136").take(3)
        val language = work.refs("P407").firstOrNull()
        val before = work.refs("P155").take(1)
        val after = work.refs("P156").take(1)
        val names = entities((work.refs("P166") + genres + before + after + listOfNotNull(language)).distinct(), "labels", "pt|en")
        fun named(ids: List<String>) = ids.mapNotNull { names[it]?.label() }
        val writtenIn = language?.let { WRITTEN_IN[it] }
        return BookFacts(
            firstPublished = work.year(),
            originalTitle = writtenIn?.let { work.label(listOf(it)) },
            originalLanguage = language?.let { names[it]?.label() },
            genres = named(genres),
            awards = named(work.refs("P166")),
            follows = named(before).firstOrNull(),
            followedBy = named(after).firstOrNull(),
            pageUrl = "https://www.wikidata.org/wiki/${work.id}",
        )
    }

    /** The item called [title] (searched in Portuguese, then English) that the person [author] (an authorKey) wrote. */
    private suspend fun findWork(title: String, author: String): JsonObject? {
        val languages = (LABEL_LANGUAGES + WRITTEN_IN.values).distinct().joinToString("|")
        for (lang in LABEL_LANGUAGES) {
            val found = api("action" to "wbsearchentities", "search" to title, "language" to lang, "uselang" to lang, "type" to "item", "limit" to "7")
            val ids = (found["search"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.content }
            if (ids.isEmpty()) continue
            val items = entities(ids, "claims|labels", languages)
            val writers = entities(ids.flatMap { items[it]?.refs("P50").orEmpty() }.distinct(), "labels", "pt|en")
            val work = ids.mapNotNull { items[it] }.firstOrNull { item ->
                item.refs("P50").any { w -> LABEL_LANGUAGES.any { l -> authorKey(writers[w]?.label(listOf(l)).orEmpty()) == author } }
            }
            if (work != null) return work
        }
        return null
    }

    private suspend fun entities(ids: List<String>, props: String, languages: String): Map<String, JsonObject> {
        val out = mutableMapOf<String, JsonObject>()
        // At most 50 ids per request.
        for (chunk in ids.chunked(50)) {
            val r = api("action" to "wbgetentities", "ids" to chunk.joinToString("|"), "props" to props, "languages" to languages)
            (r["entities"] as? JsonObject)?.forEach { (id, e) -> (e as? JsonObject)?.let { out[id] = it } }
        }
        return out
    }

    private suspend fun api(vararg params: Pair<String, String>): JsonObject {
        val query = (params.toList() + ("format" to "json")).joinToString("&") { (k, v) -> "$k=${v.encodeURLParameter()}" }
        val body = http.getOrNull("$baseUrl/w/api.php?$query") ?: return JsonObject(emptyMap())
        return json.parseToJsonElement(body) as? JsonObject ?: JsonObject(emptyMap())
    }

    private val JsonObject.id: String get() = (this["id"] as? JsonPrimitive)?.content.orEmpty()

    private fun JsonObject.claims(property: String): List<JsonObject> =
        ((this["claims"] as? JsonObject)?.get(property) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun JsonObject.value(claim: JsonObject): JsonObject? =
        ((claim["mainsnak"] as? JsonObject)?.get("datavalue") as? JsonObject)?.get("value") as? JsonObject

    /** The item ids a property points to. */
    private fun JsonObject.refs(property: String): List<String> =
        claims(property).mapNotNull { c -> (value(c)?.get("id") as? JsonPrimitive)?.content }

    private fun JsonObject.label(languages: List<String> = LABEL_LANGUAGES): String? {
        val labels = this["labels"] as? JsonObject ?: return null
        return languages.firstNotNullOfOrNull { l -> ((labels[l] as? JsonObject)?.get("value") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } }
    }

    /** "1995" from Wikidata's "+1995-00-00T00:00:00Z"; years before the common era as "BC". */
    private fun JsonObject.year(): String? {
        val time = claims("P577").firstOrNull()?.let { (value(it)?.get("time") as? JsonPrimitive)?.content } ?: return null
        val m = Regex("^([+-])0*(\\d+)-").find(time) ?: return null
        return if (m.groupValues[1] == "-") "${m.groupValues[2]} BC" else m.groupValues[2]
    }

    private companion object {
        /** Labels in Portuguese first (most of the library is Portuguese editions), then English. */
        val LABEL_LANGUAGES = listOf("pt", "en")
        /** The work's own languages, for its title as first written (Wikidata's "title" field is often wrong). */
        val WRITTEN_IN = mapOf(
            "Q5146" to "pt", "Q1860" to "en", "Q1321" to "es", "Q150" to "fr", "Q188" to "de", "Q652" to "it", "Q7737" to "ru",
            "Q5287" to "ja", "Q809" to "pl", "Q7411" to "nl", "Q9027" to "sv", "Q9035" to "da", "Q9043" to "no", "Q1412" to "fi",
            "Q9056" to "cs", "Q9176" to "ko", "Q7850" to "zh", "Q13955" to "ar", "Q256" to "tr", "Q9288" to "he", "Q9067" to "hu",
            "Q9063" to "sl", "Q7913" to "ro", "Q9129" to "el", "Q8798" to "uk", "Q6654" to "hr", "Q8748" to "ca",
        )
    }
}
