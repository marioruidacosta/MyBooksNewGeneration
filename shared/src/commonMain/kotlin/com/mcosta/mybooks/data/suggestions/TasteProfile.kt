package com.mcosta.mybooks.data.suggestions

import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.domain.searchKey

/** How many favourite authors suggestions look at. */
const val TOP_AUTHORS = 5

/** An author the reader likes: [weight] from their scores, [because] their best-rated book (for the reason shown). */
data class FavouriteAuthor(val name: String, val weight: Int, val because: Book)

data class FavouritePublisher(val name: String, val books: Int)

/** What the library says about the reader's taste. */
data class TasteProfile(
    /** Read books rated 4–5★, best first (then most recently read); without any, the read books, most recent first. */
    val favourites: List<Book>,
    /** First authors weighted 5★ = 3, 4★ = 2, read and unrated = 1 (lower scores count 0), top [TOP_AUTHORS]. */
    val authors: List<FavouriteAuthor>,
    /** Publishers with at least 3 of the reader's books ("[s.n.]" and the like aren't publishers), most books first. */
    val publishers: List<FavouritePublisher>,
)

private val NO_PUBLISHER = Regex("""\bs\.\s?n\.""", RegexOption.IGNORE_CASE)

fun tasteProfile(books: List<Book>): TasteProfile {
    val read = books.filter { it.status == ReadingStatus.READ }
    val rated = read.filter { (it.rating ?: 0) >= 4 }
        .sortedWith(compareByDescending<Book> { it.rating }.thenByDescending { it.dateRead ?: 0 })
    val favourites = rated.ifEmpty { read.sortedByDescending { it.dateRead ?: 0 } }

    val authors = read.groupBy { searchKey(firstAuthor(it)) }
        .filterKeys { it.isNotEmpty() }
        .map { (_, theirs) ->
            val best = theirs.sortedWith(compareByDescending<Book> { it.rating ?: 0 }.thenByDescending { it.dateRead ?: 0 }).first()
            FavouriteAuthor(firstAuthor(best), theirs.sumOf { weight(it.rating) }, best)
        }
        .filter { it.weight > 0 }
        .sortedWith(compareByDescending<FavouriteAuthor> { it.weight }.thenBy { searchKey(it.name) })
        .take(TOP_AUTHORS)

    val publishers = books.mapNotNull { b -> b.publisher?.trim(' ', '[', ']')?.takeIf { it.isNotEmpty() && !NO_PUBLISHER.containsMatchIn(it) } }
        .groupBy { searchKey(it) }
        .values
        .filter { it.size >= 3 }
        .map { names -> FavouritePublisher(names.groupingBy { it }.eachCount().maxBy { it.value }.key, names.size) }
        .sortedWith(compareByDescending<FavouritePublisher> { it.books }.thenBy { searchKey(it.name) })

    return TasteProfile(favourites, authors, publishers)
}

internal fun firstAuthor(book: Book) = book.authors.substringBefore(',').trim()

private fun weight(rating: Int?) = when (rating) {
    5 -> 3
    4 -> 2
    null -> 1
    else -> 0
}
