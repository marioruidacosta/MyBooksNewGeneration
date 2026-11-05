package com.mcosta.mybooks.domain

/** Longest tag kept; longer ones are cut. */
const val MAX_TAG_LENGTH = 40

/**
 * Tags as stored: trimmed, inner spaces collapsed, cut to [MAX_TAG_LENGTH], blanks dropped, and the same tag
 * written in other capitals kept once (the first spelling wins).
 */
fun cleanTags(tags: List<String>): List<String> {
    val seen = mutableSetOf<String>()
    return tags.map { it.replace(Regex("\\s+"), " ").trim().take(MAX_TAG_LENGTH).trim() }
        .filter { it.isNotEmpty() && seen.add(it.lowercase()) }
}

/** Whether [book] has [tag], ignoring capitals. */
fun Book.hasTag(tag: String): Boolean = tags.any { it.equals(tag, ignoreCase = true) }

/** A title with its series taken out: "The Hobbit (Middle-earth, #0.5)" is "The Hobbit", Middle-earth, "0.5". */
data class SeriesTitle(val title: String, val series: String, val number: String?)

// "(Series, #1)", "(Series #2.5)", "(Series, #1-3)" at the end of a title, as Goodreads writes it.
private val SERIES_IN_TITLE = Regex("""^(.*\S)\s*\(\s*([^()]*?[^\s,()])\s*,?\s*#\s*([0-9]+(?:\.[0-9]+)?(?:\s*[-–]\s*[0-9]+(?:\.[0-9]+)?)?)\s*\)\s*$""")

/** The series written at the end of [title] the Goodreads way, or null when there is none. */
fun splitSeries(title: String): SeriesTitle? {
    val m = SERIES_IN_TITLE.matchEntire(title.trim()) ?: return null
    val (rest, series, number) = m.destructured
    return SeriesTitle(rest.trim(), series.trim(), number.replace(Regex("\\s"), "").replace('–', '-'))
}

/** [title] with its series the Goodreads way, for writing a Goodreads file. */
fun withSeries(title: String, series: String?, number: String?): String = when {
    series.isNullOrBlank() -> title
    number.isNullOrBlank() -> "$title ($series)"
    else -> "$title ($series, #$number)"
}

/** Where a book goes in its series: by its number (the first of "1-3"), books without one last. */
fun seriesOrder(number: String?): Double =
    number?.substringBefore('-')?.trim()?.toDoubleOrNull() ?: Double.MAX_VALUE

/** A group of books shown together on the shelf screen, across the reading statuses. */
sealed interface BookFilter {
    fun matches(book: Book): Boolean

    data class Tag(val name: String) : BookFilter {
        override fun matches(book: Book) = book.hasTag(name)
    }

    data class Series(val name: String) : BookFilter {
        override fun matches(book: Book) = book.series.equals(name, ignoreCase = true)
    }

    /** Books lent to someone. */
    data object Lent : BookFilter {
        override fun matches(book: Book) = book.lentTo != null
    }
}
