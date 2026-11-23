package com.mcosta.mybooks.physical

import com.mcosta.mybooks.data.db.newUuid
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.domain.searchKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A bookcase is one synced preference, `bookcase.<uuid>`. The website reads and writes the same JSON. */
const val BOOKCASE_PREFIX = "bookcase."
const val MAX_BOOKCASE_NAME = 60
const val MAX_COLUMNS = 10
const val MAX_SHELVES = 15

private const val FORMAT = 1
private const val NO_ROOM = "No room"

/** Two positions closer than this have no usable midpoint: the shelf is renumbered instead. */
private const val SMALLEST_GAP = 1e-6

/** A vertical section of a bookcase; its shelves from the top. */
data class BookcaseColumn(val id: String, val shelves: List<String>)

/** [readOnly]: written by a newer version of the app, so it is shown but never saved back. */
data class Bookcase(
    val id: String,
    val name: String,
    val room: String,
    val columns: List<BookcaseColumn>,
    val readOnly: Boolean = false,
)

/** Where a shelf is: column and shelf count from 0. */
data class ShelfPlace(val bookcase: Bookcase, val column: Int, val shelf: Int, val shelfId: String) {
    val label: String get() = shelfLabel(column, shelf)
    val location: String get() = "${bookcase.room} › ${bookcase.name} › Shelf $label"
}

/** Columns are A, B, C from the left and shelves count from the top: "A2". */
fun shelfLabel(column: Int, shelf: Int): String = "${'A' + column}${shelf + 1}"

fun newBookcase(name: String, room: String, columns: Int, shelves: Int, newId: () -> String = ::newUuid): Bookcase {
    val shelvesEach = shelves.coerceIn(1, MAX_SHELVES)
    return Bookcase(
        id = newId(),
        name = name.trim().take(MAX_BOOKCASE_NAME),
        room = room.trim().take(MAX_BOOKCASE_NAME),
        columns = List(columns.coerceIn(1, MAX_COLUMNS)) { BookcaseColumn(newId(), List(shelvesEach) { newId() }) },
    )
}

@Serializable
private data class StoredColumn(val id: String, val shelves: List<String>)

@Serializable
private data class StoredBookcase(
    @SerialName("v") val version: Int = FORMAT,
    val name: String = "",
    val room: String = "",
    val columns: List<StoredColumn> = emptyList(),
)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun Bookcase.toStored(): String =
    json.encodeToString(StoredBookcase(FORMAT, name, room, columns.map { StoredColumn(it.id, it.shelves) }))

/** The bookcase stored under [key]; null when the value can't be read. */
fun parseBookcase(key: String, value: String): Bookcase? {
    if (!key.startsWith(BOOKCASE_PREFIX)) return null
    val id = key.removePrefix(BOOKCASE_PREFIX)
    if (id.isEmpty()) return null
    val stored = try {
        json.decodeFromString<StoredBookcase>(value)
    } catch (_: SerializationException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (stored.columns.isEmpty() || stored.columns.any { it.shelves.isEmpty() }) return null
    return Bookcase(
        id = id,
        name = stored.name,
        room = stored.room.trim().ifEmpty { NO_ROOM },
        columns = stored.columns.map { BookcaseColumn(it.id, it.shelves) },
        readOnly = stored.version > FORMAT,
    )
}

fun Bookcase.shelfIds(): List<String> = columns.flatMap { it.shelves }

/** A new shelf at the bottom of [column]; nothing changes when the column is full. */
fun Bookcase.withShelfAdded(column: Int, newId: () -> String = ::newUuid): Bookcase {
    val target = columns.getOrNull(column) ?: return this
    if (target.shelves.size >= MAX_SHELVES) return this
    return copy(columns = columns.mapIndexed { i, c -> if (i == column) c.copy(shelves = c.shelves + newId()) else c })
}

/** Without that shelf; a column keeps at least one. */
fun Bookcase.withoutShelf(shelfId: String): Bookcase {
    val target = columns.firstOrNull { shelfId in it.shelves } ?: return this
    if (target.shelves.size <= 1) return this
    return copy(columns = columns.map { if (it === target) it.copy(shelves = it.shelves - shelfId) else it })
}

fun Bookcase.withColumnAdded(shelves: Int, newId: () -> String = ::newUuid): Bookcase {
    if (columns.size >= MAX_COLUMNS) return this
    return copy(columns = columns + BookcaseColumn(newId(), List(shelves.coerceIn(1, MAX_SHELVES)) { newId() }))
}

/** Without that column; a bookcase keeps at least one. */
fun Bookcase.withoutColumn(column: Int): Bookcase {
    if (columns.size <= 1 || column !in columns.indices) return this
    return copy(columns = columns.filterIndexed { i, _ -> i != column })
}

fun List<Bookcase>.findShelf(shelfId: String?): ShelfPlace? {
    if (shelfId == null) return null
    for (case in this) {
        case.columns.forEachIndexed { c, column ->
            val shelf = column.shelves.indexOf(shelfId)
            if (shelf >= 0) return ShelfPlace(case, c, shelf, shelfId)
        }
    }
    return null
}

/** Rooms by name, each with its bookcases by name. Capitals don't make a different room. */
fun List<Bookcase>.byRoom(): List<Pair<String, List<Bookcase>>> =
    groupBy { it.room.lowercase() }.entries
        .sortedBy { it.key }
        .map { (_, cases) -> cases.first().room to cases.sortedBy { it.name.lowercase() } }

private val shelfOrder = compareBy<Book>({ it.shelfPosition == null }, { it.shelfPosition ?: 0.0 }, { it.title.lowercase() }, { it.id })

/** The books on a shelf, left to right. A To buy book isn't on any shelf, even if it keeps its place. */
fun List<Book>.onShelf(shelfId: String): List<Book> =
    filter { it.shelfId == shelfId && it.status != ReadingStatus.WISHLIST }.sortedWith(shelfOrder)

/** Physical books with no real shelf: not To buy, not an ebook, and on no shelf that still exists. */
fun List<Book>.withoutPlace(bookcases: List<Bookcase>, ebookIds: Set<Long>): List<Book> {
    val shelves = bookcases.flatMapTo(HashSet()) { it.shelfIds() }
    return filter { it.status != ReadingStatus.WISHLIST && it.id !in ebookIds && it.shelfId !in shelves }
}

/** A book found by [findInHome]: its shelf (null when it has none yet) and its place from the left, when it has an order. */
data class FoundBook(val book: Book, val place: ShelfPlace?, val fromLeft: Int?)

/**
 * The books with every word of [query] in their title or author, and where each one is. To buy books have no place, and
 * an ebook only appears once it was put on a shelf.
 */
fun List<Book>.findInHome(bookcases: List<Bookcase>, ebookIds: Set<Long>, query: String): List<FoundBook> {
    val words = searchKey(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val shelves = mutableMapOf<String, List<Book>>()
    return filter { book ->
        val key = searchKey("${book.title}\n${book.authors}")
        book.status != ReadingStatus.WISHLIST && words.all { it in key }
    }.mapNotNull { book ->
        val place = bookcases.findShelf(book.shelfId)
        if (place == null && book.id in ebookIds) return@mapNotNull null
        val fromLeft = if (place != null && book.shelfPosition != null) {
            shelves.getOrPut(place.shelfId) { onShelf(place.shelfId) }.indexOfFirst { it.id == book.id } + 1
        } else {
            null
        }
        FoundBook(book, place, fromLeft)
    }.sortedBy { it.book.title.lowercase() }
}

/** The position after every book on the shelf. */
fun endPosition(shelfBooks: List<Book>): Double = shelfBooks.mapNotNull { it.shelfPosition }.maxOrNull()?.plus(1.0) ?: 1.0

/** A position between two neighbours (null: that side is the end of the shelf); null when there is no room left. */
fun positionBetween(before: Double?, after: Double?): Double? = when {
    before == null && after == null -> 1.0
    before == null -> after!! - 1.0
    after == null -> before + 1.0
    after - before < SMALLEST_GAP -> null
    else -> (before + after) / 2
}

/** Whole positions in the order the books are shown. */
fun renumbered(shelfBooks: List<Book>): Map<Long, Double> =
    shelfBooks.mapIndexed { i, book -> book.id to (i + 1).toDouble() }.toMap()
