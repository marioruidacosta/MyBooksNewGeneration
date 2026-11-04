package com.mcosta.mybooks.data.suggestions

import com.mcosta.mybooks.data.authors.AuthorBook
import com.mcosta.mybooks.data.authors.AuthorBooksSource
import com.mcosta.mybooks.data.authors.findInLibrary
import com.mcosta.mybooks.data.releases.MonthBooks
import com.mcosta.mybooks.data.releases.NOT_STATED
import com.mcosta.mybooks.data.releases.ReleaseBook
import com.mcosta.mybooks.data.releases.publisherKey
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.searchKey
import com.mcosta.mybooks.social.model.CommunitySuggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where suggestions come from, in the order they're shown. */
enum class SuggestionSource(val title: String) {
    AUTHORS("From authors you love"),
    NEW_IN_PORTUGAL("New in Portugal for you"),
    COMMUNITY("Readers like you loved"),
    SUBJECTS("Like your favourites"),
}

/** A New in Portugal book, opened on its release page. */
data class ReleaseRef(val month: String, val id: String)

/** A suggested book and why; it opens as a catalogue book ([catalog]) or a New in Portugal book ([release]). */
data class Suggestion(
    val source: SuggestionSource,
    val key: String,
    val title: String,
    val authors: String,
    val coverUrl: String?,
    val reason: String,
    val catalog: AuthorBook? = null,
    val release: ReleaseRef? = null,
)

/** The suggestions of each source, in [SuggestionSource] order, without empty sections. */
data class Suggestions(val sections: List<Pair<SuggestionSource, List<Suggestion>>>) {
    /** Without the books marked "Not interested" (sections left empty disappear). */
    fun without(hidden: Set<String>) =
        Suggestions(sections.map { (s, list) -> s to list.filter { it.key !in hidden } }.filter { it.second.isNotEmpty() })

    /** Home's row: the best of each source in turn, up to [max]. */
    fun mix(max: Int = HOME_SUGGESTIONS): List<Suggestion> {
        val mixed = mutableListOf<Suggestion>()
        var i = 0
        while (mixed.size < max && sections.any { i < it.second.size }) {
            for ((_, list) in sections) if (i < list.size && mixed.size < max) mixed += list[i]
            i++
        }
        return mixed
    }

    companion object {
        val NONE = Suggestions(emptyList())
    }
}

/** How many suggestions Home's row shows. */
const val HOME_SUGGESTIONS = 12

/** At most this many suggestions per section (and per author, per subject). */
private const val PER_SECTION = 15
private const val PER_AUTHOR = 4
private const val PER_SUBJECT = 5
private const val SUBJECTS = 3
/** Favourites whose subjects are looked up. */
private const val SUBJECT_FAVOURITES = 8

/**
 * Suggestions from the library: other books by favourite authors, recent New in Portugal books by authors and
 * publishers the reader has, books community readers with the same favourites loved, and books on the
 * favourites' subjects. Books already in the library never appear, and a book appears in one section only. The
 * sources run at once; one that fails (e.g. offline) just has no section. Kept until [suggestions] with refresh.
 */
class SuggestionEngine(
    private val library: suspend () -> List<Book>,
    private val authorBooks: AuthorBooksSource? = null,
    private val subjects: SubjectSource? = null,
    private val community: (suspend (List<String>) -> List<CommunitySuggestion>)? = null,
    private val releases: (suspend () -> List<MonthBooks>)? = null,
) {
    private val lock = Mutex()
    private var cached: Suggestions? = null

    suspend fun suggestions(refresh: Boolean = false): Suggestions = lock.withLock {
        if (!refresh) cached?.let { return it }
        compute().also { cached = it }
    }

    private suspend fun compute(): Suggestions = coroutineScope {
        val books = library()
        val taste = tasteProfile(books)
        val authors = async { quietly { fromAuthors(taste, books) } }
        val newBooks = async { quietly { fromNewInPortugal(taste, books) } }
        val fans = async { quietly { fromCommunity(taste, books) } }
        val similar = async { quietly { fromSubjects(taste, books) } }
        val seen = mutableSetOf<String>()
        val sections = listOf(
            SuggestionSource.AUTHORS to authors.await(),
            SuggestionSource.NEW_IN_PORTUGAL to newBooks.await(),
            SuggestionSource.COMMUNITY to fans.await(),
            SuggestionSource.SUBJECTS to similar.await(),
        ).map { (source, list) -> source to list.filter { seen.add(it.key) }.take(PER_SECTION) }
        Suggestions(sections.filter { it.second.isNotEmpty() })
    }

    private suspend fun fromAuthors(taste: TasteProfile, books: List<Book>): List<Suggestion> {
        val source = authorBooks ?: return emptyList()
        return taste.authors.flatMap { author ->
            source.booksBy(author.name).filter { findInLibrary(it, books) == null }.take(PER_AUTHOR).map {
                catalogSuggestion(SuggestionSource.AUTHORS, it, "Because you loved ${author.because.title}")
            }
        }
    }

    private suspend fun fromSubjects(taste: TasteProfile, books: List<Book>): List<Suggestion> = coroutineScope {
        val source = subjects ?: return@coroutineScope emptyList()
        val favourites = taste.favourites.filter { it.isbn != null }.take(SUBJECT_FAVOURITES)
        val perBook = favourites.map { b -> async { b to quietly { source.subjectsOf(b.isbn!!, b.authors) } } }.map { it.await() }
        // The subjects most favourites share, then in the favourites' order.
        val chosen = perBook.flatMap { (book, subjects) -> subjects.map { searchKey(it) to (it to book) } }
            .groupBy({ it.first }, { it.second })
            .entries.sortedByDescending { it.value.size }
            .take(SUBJECTS)
            .map { it.value.first() }
        chosen.map { (subject, book) -> async { subject to book to quietly { source.booksOn(subject) } } }.map { it.await() }
            .flatMap { (pair, found) ->
                val (subject, book) = pair
                found.filter { findInLibrary(it, books) == null }.take(PER_SUBJECT).map {
                    catalogSuggestion(SuggestionSource.SUBJECTS, it, "Like ${book.title} · $subject")
                }
            }
    }

    private suspend fun fromCommunity(taste: TasteProfile, books: List<Book>): List<Suggestion> {
        val source = community ?: return emptyList()
        val liked = taste.favourites.mapNotNull { it.isbn }.distinct().take(50)
        if (liked.isEmpty()) return emptyList()
        val titles = books.filter { it.isbn != null }.associate { it.isbn!! to it.title }
        return source(liked).map { s ->
            AuthorBook(s.title, s.authors, null, null, s.isbn, s.coverUrl, null, null) to s
        }.filter { findInLibrary(it.first, books) == null }.map { (book, s) ->
            val readers = if (s.readers == 1) "1 reader" else "${s.readers} readers"
            catalogSuggestion(SuggestionSource.COMMUNITY, book, "$readers who loved ${titles[s.because] ?: "your favourite"} loved this")
        }
    }

    private suspend fun fromNewInPortugal(taste: TasteProfile, books: List<Book>): List<Suggestion> {
        val months = releases?.invoke() ?: return emptyList()
        val byAuthor = books.groupBy { searchKey(firstAuthor(it)) }.filterKeys { it.isNotEmpty() }
        val publishers = taste.publishers.associateBy { searchKey(it.name) }
        fun ReleaseBook.firstAuthor() = authors.firstOrNull().orEmpty().trim()
        fun ReleaseBook.inLibrary() = books.any { b -> (isbn.isNotBlank() && b.isbn == isbn) } ||
            findInLibrary(AuthorBook(title, authors.joinToString(", "), null, null, null, null, null, null), books) != null
        val matches = months.flatMap { month -> month.books.map { month.month to it } }
            .filter { (_, b) -> !b.inLibrary() }
            .mapNotNull { (month, b) ->
                val theirs = byAuthor[searchKey(b.firstAuthor())]
                val publisher = b.publisherKey().takeIf { it != NOT_STATED }?.let { publishers[searchKey(it)] }
                val reason = when {
                    theirs != null -> "New from ${b.firstAuthor()} · you have ${count(theirs.size)} of their books"
                    publisher != null -> "New from ${publisher.name} · you have ${count(publisher.books)} of their books"
                    else -> return@mapNotNull null
                }
                Triple(theirs != null, month, Suggestion(
                    SuggestionSource.NEW_IN_PORTUGAL, suggestionKey(b.isbn, b.title, b.authors.joinToString(", ")),
                    b.title, b.authors.joinToString(", "), b.listCover, reason, release = ReleaseRef(month, b.id),
                ))
            }
        // Their authors first, then their publishers; newest month first within each.
        return matches.sortedWith(compareByDescending<Triple<Boolean, String, Suggestion>> { it.first }.thenByDescending { it.second })
            .map { it.third }
    }

    private fun catalogSuggestion(source: SuggestionSource, book: AuthorBook, reason: String) =
        Suggestion(source, suggestionKey(book.isbn, book.title, book.authors), book.title, book.authors, book.coverUrl, reason, catalog = book)

    private fun count(n: Int) = if (n == 1) "1" else "$n"

    private suspend fun <T> quietly(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }
}
