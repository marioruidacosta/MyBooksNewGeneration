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
