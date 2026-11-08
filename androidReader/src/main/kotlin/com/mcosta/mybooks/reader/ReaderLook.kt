package com.mcosta.mybooks.reader

import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.domain.EbookFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

const val MIN_FONT_SIZE = 0.7
const val MAX_FONT_SIZE = 2.5
private const val FONT_STEP = 0.1

enum class LookTheme { LIGHT, SEPIA, DARK }

/** The book's own font, or a serif or sans-serif one. */
enum class LookFont { BOOK, SERIF, SANS }

/** How EPUB text looks: size (1.0 is the book's), font, line spacing (null is the book's) and colours. */
data class ReadingLook(
    val fontSize: Double = 1.0,
    val font: LookFont = LookFont.BOOK,
    val lineHeight: Double? = null,
    val theme: LookTheme = LookTheme.LIGHT,
) {
    fun bigger() = copy(fontSize = ((fontSize + FONT_STEP) * 10).roundToInt() / 10.0).clamped()
    fun smaller() = copy(fontSize = ((fontSize - FONT_STEP) * 10).roundToInt() / 10.0).clamped()
    private fun clamped() = copy(fontSize = fontSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE))
}

/** The reading look, one for every book, kept in [Preferences] (`reader.look`). */
class ReadingLooks(private val prefs: Preferences?) {
    private val _look = MutableStateFlow(decode(prefs?.get(KEY)))
    val look: StateFlow<ReadingLook> = _look.asStateFlow()

    fun set(look: ReadingLook) {
        _look.value = look
        prefs?.set(KEY, encode(look))
    }

    private companion object {
        const val KEY = "reader.look"

        fun encode(l: ReadingLook) =
            "size=${l.fontSize};font=${l.font.name.lowercase()};line=${l.lineHeight ?: ""};theme=${l.theme.name}"

        /** Each part is read on its own: a damaged one falls back to its default. */
        fun decode(text: String?): ReadingLook {
            val parts = text.orEmpty().split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val d = ReadingLook()
            return ReadingLook(
                fontSize = parts["size"]?.toDoubleOrNull()?.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE) ?: d.fontSize,
                font = LookFont.entries.firstOrNull { it.name.equals(parts["font"], ignoreCase = true) } ?: d.font,
                lineHeight = parts["line"]?.toDoubleOrNull(),
                theme = LookTheme.entries.firstOrNull { it.name == parts["theme"] } ?: d.theme,
            )
        }
    }
}

/** "Chapter 3 · 42%" for an EPUB, "Page 12 of 240" for a PDF; just the percentage when that's all there is. */
fun positionLabel(format: EbookFormat, chapter: String?, page: Int?, progression: Double, pages: Int): String {
    val percent = "${(progression * 100).roundToInt().coerceIn(0, 100)}%"
    return when (format) {
        EbookFormat.PDF -> if (page != null && pages > 0) "Page $page of $pages" else percent
        EbookFormat.EPUB -> listOfNotNull(chapter?.trim()?.takeIf { it.isNotEmpty() }, percent).joinToString(" · ")
    }
}

/** A table-of-contents entry, apart from Readium: its title, where it goes ([target]), and its sub-entries. */
data class TocEntry(val title: String, val target: String, val children: List<TocEntry> = emptyList())

data class TocRow(val entry: TocEntry, val depth: Int)

/** The contents as a list, sub-entries indented by [TocRow.depth]; entries without a title are left out. */
fun flattenContents(toc: List<TocEntry>, depth: Int = 0): List<TocRow> = toc.flatMap { e ->
    (if (e.title.isBlank()) emptyList() else listOf(TocRow(e, depth))) + flattenContents(e.children, depth + 1)
}

/** A bookmark's name: the chapter for an EPUB, the page for a PDF, else how far through the book. */
fun bookmarkLabel(format: EbookFormat, chapter: String?, page: Int?, progression: Double): String = when {
    format == EbookFormat.PDF && page != null -> "Page $page"
    !chapter.isNullOrBlank() -> chapter.trim()
    else -> "${(progression * 100).roundToInt().coerceIn(0, 100)}%"
}
