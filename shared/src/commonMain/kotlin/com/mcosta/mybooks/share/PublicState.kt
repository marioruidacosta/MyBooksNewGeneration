package com.mcosta.mybooks.share

import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.social.model.PublicBookState

/** The server's limit for a shared title or authors. */
private const val MAX_SHARED_TEXT = 300

/** The server's limit for a shared blurb. */
const val BLURB_MAX = 600

private val SPACES = Regex("""\s+""")

/** The start of a book's description for its feed card: whitespace collapsed, at most [BLURB_MAX]; null if blank. */
fun blurbOf(description: String?): String? =
    description?.replace(SPACES, " ")?.trim()?.take(BLURB_MAX)?.trim()?.takeIf { it.isNotEmpty() }

/** What the community sees of this book. Cover photos taken in the app are local files, so they're left out. */
fun Book.publicState(shareId: String) = PublicBookState(
    shareId = shareId,
    isbn = isbn,
    title = title.take(MAX_SHARED_TEXT),
    authors = authors.take(MAX_SHARED_TEXT),
    coverUrl = coverUrl?.takeIf { it.startsWith("https://") },
    status = status,
    rating = rating,
    progress = progressPercent,
    review = review,
    blurb = blurbOf(description),
    pages = pageCount,
    currentPage = currentPage,
)

/** Whether anything the community sees differs (notes, dates and the like don't count). */
fun publicFieldsDiffer(a: Book, b: Book): Boolean =
    a.status != b.status || a.rating != b.rating || a.progressPercent != b.progressPercent || a.review != b.review ||
        a.title != b.title || a.authors != b.authors || a.coverUrl != b.coverUrl ||
        a.description != b.description || a.pageCount != b.pageCount || a.currentPage != b.currentPage
