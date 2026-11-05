package com.mcosta.mybooks.data.goodreads

import com.mcosta.mybooks.data.MAX_REVIEW_LENGTH
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.Isbn
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.domain.cleanTags
import com.mcosta.mybooks.domain.splitSeries
import com.mcosta.mybooks.domain.withSeries
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Instant

class GoodreadsFormatException(message: String) : Exception(message)

/**
 * Goodreads' library CSV (My Books → Import and export): reading its export into books, and writing the
 * library in the same format for its import. Dates are calendar days in [TimeZone].
 */
object GoodreadsCsv {
    /** Goodreads' extra shelf for "To buy" (it has no such status). */
    private const val TO_BUY_SHELF = "to-buy"
    private val STATUS_SHELVES = setOf("read", "currently-reading", "to-read", TO_BUY_SHELF)
    private val COLUMNS = listOf(
        "Title", "Author", "Additional Authors", "ISBN", "ISBN13", "My Rating", "Publisher", "Number of Pages",
        "Year Published", "Date Read", "Date Added", "Bookshelves", "Exclusive Shelf", "My Review",
    )

    /** Every row as a book (a row without a title gives an untitled book, for the importer to reject). */
    fun read(text: String, timeZone: TimeZone = TimeZone.currentSystemDefault()): List<Book> {
        val rows = Csv.parse(text)
        val header = rows.firstOrNull().orEmpty().map { it.trim() }
        if ("Title" !in header || "Author" !in header || "Exclusive Shelf" !in header) {
            throw GoodreadsFormatException("Not a Goodreads library export")
        }
        return rows.drop(1).map { cells ->
            val row = { name: String -> header.indexOf(name).takeIf { it >= 0 }?.let { cells.getOrNull(it) }?.trim().orEmpty() }
            val shelves = row("Bookshelves").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val series = splitSeries(row("Title"))
            Book(
                title = series?.title ?: row("Title"),
                series = series?.series,
                seriesNumber = series?.number,
                // Shelves other than the reading ones (and To buy, which is a status here) are tags.
                tags = cleanTags(shelves.filter { it.lowercase() !in STATUS_SHELVES }),
                authors = (listOf(row("Author")) + row("Additional Authors").split(',')).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", "),
                isbn = Isbn.normalize(unquoteIsbn(row("ISBN13"))) ?: Isbn.normalize(unquoteIsbn(row("ISBN"))),
                // Goodreads writes "5.0" and, for half and quarter stars, "4.5" or "4.25": rounded to whole stars.
                rating = row("My Rating").toDoubleOrNull()?.roundToInt()?.takeIf { it in 1..5 },
                publisher = row("Publisher").ifEmpty { null },
                pageCount = row("Number of Pages").toIntOrNull()?.takeIf { it > 0 },
                publishedYear = (row("Year Published").ifEmpty { row("Original Publication Year") }).toIntOrNull(),
                status = when (row("Exclusive Shelf")) {
                    "read" -> ReadingStatus.READ
                    "currently-reading" -> ReadingStatus.READING
                    else -> if (TO_BUY_SHELF in shelves) ReadingStatus.WISHLIST else ReadingStatus.TO_READ
                },
                dateRead = date(row("Date Read"), timeZone),
                dateAdded = date(row("Date Added"), timeZone) ?: 0,
                review = plainText(row("My Review")).take(MAX_REVIEW_LENGTH),
                notes = row("Private Notes"),
            )
        }
    }

    /** The books in Goodreads' format; private notes are left out. */
    fun write(books: List<Book>, timeZone: TimeZone = TimeZone.currentSystemDefault()): String =
        Csv.write(listOf(COLUMNS) + books.map { b ->
            val authors = b.authors.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            listOf(
                withSeries(b.title, b.series, b.seriesNumber),
                authors.firstOrNull().orEmpty(),
                authors.drop(1).joinToString(", "),
                "",
                b.isbn.orEmpty(),
                (b.rating ?: 0).toString(),
                b.publisher.orEmpty(),
                b.pageCount?.toString().orEmpty(),
                b.publishedYear?.toString().orEmpty(),
                b.dateRead?.takeIf { b.status == ReadingStatus.READ }?.let { day(it, timeZone) }.orEmpty(),
                b.dateAdded.takeIf { it > 0 }?.let { day(it, timeZone) }.orEmpty(),
                (b.tags.map { shelfName(it) } + listOfNotNull(TO_BUY_SHELF.takeIf { b.status == ReadingStatus.WISHLIST })).joinToString(", "),
                when (b.status) {
                    ReadingStatus.READ -> "read"
                    ReadingStatus.READING -> "currently-reading"
                    ReadingStatus.TO_READ, ReadingStatus.WISHLIST -> "to-read"
                },
                html(b.review),
            )
        })

    /** Goodreads shelf names have no commas or spaces: "Gift from Ana" goes as "gift-from-ana". */
    private fun shelfName(tag: String) = tag.lowercase().replace(Regex("[,\\s]+"), "-")

    /** Goodreads writes ISBNs as ="9780441013593" so spreadsheets keep them as text. */
    private fun unquoteIsbn(value: String) = value.removePrefix("=").trim('"')

    /** "2024/03/15" (Goodreads' format), as the start of that day. */
    private fun date(value: String, timeZone: TimeZone): Long? {
        val parts = value.split('/', '-').mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return null
        return runCatching { LocalDate(parts[0], parts[1], parts[2]) }.getOrNull()?.atStartOfDayIn(timeZone)?.toEpochMilliseconds()
    }

    private fun day(epochMs: Long, timeZone: TimeZone): String {
        val d = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone).date
        return "${d.year}/${pad(d.month.number)}/${pad(d.day)}"
    }

    private fun pad(n: Int) = n.toString().padStart(2, '0')

    private val BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]*>""")

    /** Goodreads reviews are HTML: line breaks kept, other tags dropped, entities decoded. */
    private fun plainText(html: String): String = html.replace(BREAK, "\n").replace(TAG, "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .trim()

    private fun html(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br/>")
}
