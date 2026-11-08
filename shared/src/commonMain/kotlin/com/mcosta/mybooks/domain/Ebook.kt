package com.mcosta.mybooks.domain

/** The ebook formats MyBooks can read. Stored by name. */
enum class EbookFormat(val extension: String) { EPUB("epub"), PDF("pdf") }

/**
 * A book's ebook file: one per book, kept on the phone (never in backups). [position] is the reader's last place
 * (a Readium locator as JSON), null before the book is first opened.
 */
data class Ebook(
    val bookId: Long,
    val format: EbookFormat,
    /** The file's original name, as shown on the book page. */
    val fileName: String,
    val size: Long,
    val dateAdded: Long,
    val position: String? = null,
    val lastOpened: Long? = null,
)

/** A place kept in an ebook: its [position] (locator JSON) and a [label] such as the chapter title or page. */
data class Bookmark(
    val id: Long = 0,
    val bookId: Long,
    val position: String,
    val label: String,
    val dateAdded: Long,
)

/** What an ebook file says about itself; any of it may be missing. */
data class EbookMetadata(
    val title: String?,
    val authors: List<String>,
    val isbn: String?,
    val language: String?,
)

/** Generational suffixes kept after a name ("Jr.", "III"); anything else after the given names is a title or dates. */
private val NAME_SUFFIX = Regex("""(?i)(jr|sr)\.?|[ivx]{1,4}""")

/**
 * "Queirós, Eça de" (as catalogues and many ebooks write names) → "Eça de Queirós"; "King, Martin Luther, Jr." →
 * "Martin Luther King Jr."; "Tolstoy, Leo, graf" → "Leo Tolstoy". Never keeps a comma, as the library reads a comma
 * as the start of another author. Other names are only trimmed.
 */
fun personName(raw: String): String {
    val parts = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size < 2) return parts.firstOrNull().orEmpty()
    val suffixes = parts.drop(2).filter { NAME_SUFFIX.matches(it) }
    return (listOf(parts[1], parts[0]) + suffixes).joinToString(" ")
}

/** A title from a file name: "Os_Maias.epub" → "Os Maias". */
fun titleFromFileName(name: String): String =
    name.substringAfterLast('/').substringAfterLast('\\')
        .let { if ('.' in it && it.substringAfterLast('.').length in 2..5) it.substringBeforeLast('.') else it }
        .replace('_', ' ').replace(Regex("""\s+"""), " ").trim()
