package com.mcosta.mybooks.domain

/** The longest quote kept. */
const val MAX_QUOTE_LENGTH = 2000

/** A passage saved from a book, with the page it is on when known. */
data class Quote(
    val id: Long = 0,
    val bookId: Long,
    val text: String,
    val page: Int? = null,
    val dateAdded: Long = 0,
)

/**
 * Text read from a photo of a page, tidied for a quote: lines broken only by the page's width are joined, a word
 * hyphenated across lines is put back together, and paragraphs (blank lines) are kept.
 */
fun tidyPageText(text: String): String =
    text.replace("\r\n", "\n")
        .split(Regex("\n\\s*\n"))
        .map { paragraph ->
            paragraph.lines().map { it.trim() }.filter { it.isNotEmpty() }
                .fold("") { joined, line ->
                    when {
                        joined.isEmpty() -> line
                        // "conven-\ntion" was one word; "well-\nknown" loses its hyphen too, which is rarer.
                        joined.endsWith("-") && joined.length > 1 && joined[joined.length - 2].isLetter() &&
                            line.first().isLowerCase() -> joined.dropLast(1) + line
                        else -> "$joined $line"
                    }
                }
        }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")
