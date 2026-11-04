package com.mcosta.mybooks.data.releases

import kotlinx.datetime.LocalDate
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable

/** Where the nightly releases job publishes its files (GitHub Pages). */
const val RELEASES_BASE_URL = "https://marioruidacosta.github.io/MyBooks/releases/"

/** The genres of New in Portugal, in display order (keys as published by the releases job). */
enum class ReleaseGenre(val key: String, val label: String) {
    CRIME("crime", "Crime & mystery"),
    FANTASY("fantasy", "Fantasy"),
    SCIFI("scifi", "Science fiction"),
    HISTORICAL("historical", "Historical novel"),
    ADVENTURE("adventure", "Adventure & travel novels"),
    NOVEL("novel", "Novel"),
    STORIES("stories", "Short stories & tales"),
    POETRY("poetry", "Poetry"),
    THEATRE("theatre", "Theatre"),
    ESSAYS("essays", "Essays"),
    BIOGRAPHY("biography", "Biography, memoirs & chronicles"),
    HUMOUR("humour", "Humour & satire"),
    LITERATURE("literature", "Other literature"),
    LANGUAGE("language", "Language & linguistics"),
    CHILDREN("children", "Children's & young adult"),
    GENERAL("general", "General, information & computing"),
    PHILOSOPHY("philosophy", "Philosophy & psychology"),
    RELIGION("religion", "Religion"),
    SOCIAL("social", "Social sciences, law & economics"),
    SCIENCE("science", "Science & mathematics"),
    APPLIED("applied", "Applied sciences, health & cooking"),
    ARTS("arts", "Arts, music & sport"),
    HISTORY("history", "History, geography & biography"),
    OTHER("other", "Other"),
    UNCLASSIFIED("unclassified", "Not yet classified");

    companion object {
        /** A key the app doesn't know (a newer job) counts as Other. */
        fun of(key: String): ReleaseGenre = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/** One month of New in Portugal: [genres] in display order, only those with books. */
data class ReleaseMonth(val month: String, val complete: Boolean, val total: Int, val genres: List<Pair<ReleaseGenre, Int>>)

/** A new book, as published in the month files. */
@Serializable
data class ReleaseBook(
    val id: String,
    val isbn: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val translators: List<String> = emptyList(),
    val publisher: String? = null,
    val place: String? = null,
    val year: Int? = null,
    val genre: String = "other",
    val link: String? = null,
    /** The National Library's cover image, when it has one. */
    val cover: String? = null,
) {
    val genreType: ReleaseGenre get() = ReleaseGenre.of(genre)

    /** The cover to show in lists: the Library's, else Open Library's by ISBN (a placeholder when it has none). */
    val listCover: String get() = cover ?: openLibraryCover(isbn)
}

/** Open Library's cover for an ISBN; a 404 (no cover) shows the placeholder. */
fun openLibraryCover(isbn: String) = "https://covers.openlibrary.org/b/isbn/$isbn-M.jpg?default=false"

data class StoreLink(val name: String, val url: String)

/** The bookshops of "Where to buy", each opening its own search for [query]: an ISBN, or a title and author. */
fun storeLinks(query: String): List<StoreLink> {
    // An ISBN goes through unchanged; a title and author are encoded for each shop's search.
    val path = query.trim().encodeURLPathPart()
    val param = query.trim().encodeURLParameter(spaceToPlus = true)
    return listOf(
        StoreLink("Bertrand", "https://www.bertrand.pt/pesquisa/$path"),
        StoreLink("Almedina", "https://www.almedina.net/catalogsearch/result/?q=$param"),
        StoreLink("Wook", "https://www.wook.pt/pesquisa?keyword=$param"),
        StoreLink("Fnac", "https://www.fnac.pt/SearchResult/ResultList.aspx?Search=$param"),
    )
}

/** "November 2026 (so far)" for the month in progress, "October 2026" otherwise. [month] is `YYYY-MM`. */
fun monthLabel(month: String, complete: Boolean): String {
    val date = LocalDate.parse("$month-01")
    val name = date.month.name.lowercase().replaceFirstChar { it.uppercase() }
    return "$name ${date.year}" + if (complete) "" else " (so far)"
}

@Serializable
internal data class IndexJson(val months: List<IndexMonthJson> = emptyList())

@Serializable
internal data class IndexMonthJson(val month: String, val complete: Boolean = true, val total: Int = 0, val genres: Map<String, Int> = emptyMap())

@Serializable
internal data class MonthJson(val month: String, val books: List<ReleaseBook> = emptyList())

internal fun IndexMonthJson.toMonth(): ReleaseMonth {
    val counts = genres.entries.groupBy({ ReleaseGenre.of(it.key) }, { it.value }).mapValues { it.value.sum() }
    return ReleaseMonth(month, complete, total, ReleaseGenre.entries.mapNotNull { g -> counts[g]?.takeIf { it > 0 }?.let { g to it } })
}
