package com.mcosta.mybooks.reader

import org.readium.r2.shared.publication.services.search.search

/** Searching a book's text, apart from Readium: [start] a query, then [next] batches of matches until null. */
interface BookSearch {
    suspend fun start(query: String)
    suspend fun next(): List<SearchHit>?
    /** Done with this book's search. */
    suspend fun close() {}
}

/** A match: where it is (locator JSON), the text around it, and its chapter. */
data class SearchHit(val position: String, val before: String, val match: String, val after: String, val chapter: String?)

/** What the search sheet shows. */
data class SearchState(
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val searching: Boolean = false,
    /** No more matches to fetch. */
    val done: Boolean = true,
)

/** The words around a match, for a result row. */
data class SearchSnippet(val before: String, val match: String, val after: String)

/** How many characters of text a result shows on each side of the match. */
private const val AROUND = 30

/** Results fetched before "Show more" (whole chapters, so a few more is fine). */
const val SEARCH_PAGE = 20

private val SPACES = Regex("""\s+""")

/** "…on and on before the word " + match + " and then the text continues…": cut at whole words, line breaks as spaces. */
fun searchSnippet(before: String, match: String, after: String): SearchSnippet {
    val b = before.replace(SPACES, " ")
    val a = after.replace(SPACES, " ")
    val shownBefore = if (b.length <= AROUND) b else "…" + b.takeLast(AROUND).let { it.substring(it.indexOf(' ').coerceAtLeast(0)).trimStart() }
    val shownAfter = if (a.length <= AROUND) a else a.take(AROUND).let { cut -> cut.substring(0, cut.lastIndexOf(' ').takeIf { it > 0 } ?: cut.length).trimEnd() } + "…"
    return SearchSnippet(shownBefore, match.replace(SPACES, " "), shownAfter)
}

/** [BookSearch] over a Readium publication (EPUB); a PDF has no search, so it finds nothing. */
@OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
class ReadiumSearch(private val publication: () -> org.readium.r2.shared.publication.Publication?) : BookSearch {
    private var iterator: org.readium.r2.shared.publication.services.search.SearchIterator? = null

    override suspend fun start(query: String) {
        iterator?.close()
        iterator = publication()?.search(query)
    }

    override suspend fun close() {
        iterator?.close()
        iterator = null
    }

    override suspend fun next(): List<SearchHit>? {
        val page = iterator?.next()?.getOrNull() ?: return null
        // An empty page means the search has nothing more.
        if (page.locators.isEmpty()) return null
        return page.locators.map { l ->
            SearchHit(l.toJSON().toString(), l.text.before.orEmpty(), l.text.highlight.orEmpty(), l.text.after.orEmpty(), l.title)
        }
    }
}
