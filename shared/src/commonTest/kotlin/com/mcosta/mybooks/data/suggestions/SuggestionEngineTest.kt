package com.mcosta.mybooks.data.suggestions

import com.mcosta.mybooks.data.authors.AuthorBook
import com.mcosta.mybooks.data.releases.MonthBooks
import com.mcosta.mybooks.data.releases.ReleaseBook
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.social.model.CommunitySuggestion
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SuggestionEngineTest {
    private var id = 1L
    private fun read(title: String, author: String, rating: Int?, isbn: String? = null, publisher: String? = null) =
        Book(id = id++, title = title, authors = author, rating = rating, isbn = isbn, publisher = publisher, status = ReadingStatus.READ, dateRead = id)
    private fun catalog(title: String, author: String, isbn: String? = null) = AuthorBook(title, author, 2020, null, isbn, null, null, null)
    private fun release(id: String, title: String, author: String, publisher: String) =
        ReleaseBook(id = id, isbn = "97897200000$id", title = title, authors = listOf(author), publisher = publisher, genre = "novel")

    private val library = listOf(
        read("Memorial do Convento", "José Saramago", 5, "9789720046857", "Porto Editora"),
        read("Caim", "José Saramago", 4, "9789722120005", "Porto Editora"),
        read("Solaris", "Stanisław Lem", 4, "9789726083962", "Antígona"),
        read("A Peste", "Albert Camus", 2, null, "Antígona"),
        Book(id = 99, title = "Livro do Desassossego", authors = "Fernando Pessoa", publisher = "Antígona", status = ReadingStatus.TO_READ),
    )

    private val byAuthor = mapOf(
        "José Saramago" to listOf(catalog("Caim", "José Saramago", "9789722120005"), catalog("Ensaio sobre a Cegueira", "José Saramago", "9789722113922")),
        "Stanisław Lem" to listOf(catalog("Fiasco", "Stanisław Lem")),
    )

    private fun engine(
        community: (suspend (List<String>) -> List<CommunitySuggestion>)? = null,
        releases: (suspend () -> List<MonthBooks>)? = null,
        subjects: SubjectSource? = null,
    ) = SuggestionEngine(
        library = { library },
        authorBooks = { name -> byAuthor[name].orEmpty() },
        subjects = subjects,
        community = community,
        releases = releases,
    )

    @Test
    fun otherBooksByFavouriteAuthorsNotTheOnesOwned() = runTest {
        val authors = engine().suggestions().sections.single { it.first == SuggestionSource.AUTHORS }.second
        assertEquals(listOf("Ensaio sobre a Cegueira", "Fiasco"), authors.map { it.title })
        assertEquals("Because you loved Memorial do Convento", authors[0].reason)
        assertEquals("Ensaio sobre a Cegueira", authors[0].catalog?.title)
    }

    @Test
    fun newInPortugalByOwnedAuthorsThenFavouritePublishers() = runTest {
        val months = listOf(
            MonthBooks("2026-11", false, listOf(release("1", "Novo Saramago", "José Saramago", "Porto Editora"), release("2", "Novo Antígona", "Someone New", "Antígona"))),
            MonthBooks("2026-10", true, listOf(release("3", "Outro Pessoa", "Fernando Pessoa", "Assírio"), release("4", "Nada", "Nobody", "Asa"))),
        )
        val list = engine(releases = { months }).suggestions().sections.single { it.first == SuggestionSource.NEW_IN_PORTUGAL }.second
        assertEquals(listOf("Novo Saramago", "Outro Pessoa", "Novo Antígona"), list.map { it.title })
        assertEquals("New from José Saramago · you have 2 of their books", list[0].reason)
        assertEquals("New from Antígona · you have 3 of their books", list[2].reason)
        assertEquals(ReleaseRef("2026-11", "1"), list[0].release)
    }

    @Test
    fun communitySendsFavouritesAndExplainsWhy() = runTest {
        var asked = emptyList<String>()
        val community: suspend (List<String>) -> List<CommunitySuggestion> = { liked ->
            asked = liked
            listOf(
                CommunitySuggestion("9780000000002", "Duna", "Frank Herbert", null, 3, "9789726083962"),
                CommunitySuggestion("9789722120005", "Caim", "José Saramago", null, 2, "9789720046857"),   // owned
            )
        }
        val list = engine(community = community).suggestions().sections.single { it.first == SuggestionSource.COMMUNITY }.second
        assertEquals(listOf("9789720046857", "9789726083962", "9789722120005"), asked)   // best first, then most recently read
        assertEquals(listOf("Duna"), list.map { it.title })
        assertEquals("3 readers who loved Solaris loved this", list[0].reason)
    }

    @Test
    fun subjectsSharedByMostFavouritesFirst() = runTest {
        val subjects = object : SubjectSource {
            override suspend fun subjectsOf(isbn: String, authors: String) = when (isbn) {
                "9789720046857" -> listOf("Portuguese literature", "Religion")
                "9789722120005" -> listOf("Religion", "Bible")
                "9789726083962" -> listOf("Life on other planets")
                else -> emptyList()
            }
            override suspend fun booksOn(subject: String) = when (subject) {
                "Religion" -> listOf(catalog("O Evangelho", "Someone"), catalog("Caim", "José Saramago", "9789722120005"))
                "Portuguese literature" -> listOf(catalog("Os Maias", "Eça de Queirós"))
                else -> listOf(catalog("Contact", "Carl Sagan"))
            }
        }
        val list = engine(subjects = subjects).suggestions().sections.single { it.first == SuggestionSource.SUBJECTS }.second
        assertEquals(listOf("O Evangelho", "Os Maias", "Contact"), list.map { it.title })
        assertEquals("Like Memorial do Convento · Religion", list[0].reason)
    }

    @Test
    fun aBookAppearsOnceAndFailingSourcesAreLeftOut() = runTest {
        val subjects = object : SubjectSource {
            override suspend fun subjectsOf(isbn: String, authors: String): List<String> = throw IllegalStateException("offline")
            override suspend fun booksOn(subject: String) = emptyList<AuthorBook>()
        }
        val community: suspend (List<String>) -> List<CommunitySuggestion> = {
            listOf(CommunitySuggestion("9789722113922", "Ensaio sobre a Cegueira", "José Saramago", null, 1, "9789720046857"))
        }
        val s = engine(community = community, subjects = subjects, releases = { throw IllegalStateException("offline") }).suggestions()
        assertEquals(listOf(SuggestionSource.AUTHORS), s.sections.map { it.first })   // Ensaio already suggested by its author
    }

    @Test
    fun keptUntilRefreshedAndHiddenOnesLeftOut() = runTest {
        var calls = 0
        val engine = SuggestionEngine(library = { calls++; library }, authorBooks = { name -> byAuthor[name].orEmpty() })
        val first = engine.suggestions()
        engine.suggestions()
        assertEquals(1, calls)
        engine.suggestions(refresh = true)
        assertEquals(2, calls)
        val hidden = first.without(setOf("9789722113922"))
        assertEquals(listOf("Fiasco"), hidden.sections.single().second.map { it.title })
        assertEquals(emptyList(), first.without(setOf("9789722113922", suggestionKey(null, "Fiasco", "Stanisław Lem"))).sections)
    }

    @Test
    fun homeMixesTheSourcesInTurn() {
        fun s(source: SuggestionSource, n: Int) = List(n) { Suggestion(source, "$source$it", "$source$it", "", null, "") }
        val all = Suggestions(listOf(SuggestionSource.AUTHORS to s(SuggestionSource.AUTHORS, 3), SuggestionSource.COMMUNITY to s(SuggestionSource.COMMUNITY, 1)))
        assertEquals(listOf("AUTHORS0", "COMMUNITY0", "AUTHORS1", "AUTHORS2"), all.mix().map { it.title })
        assertEquals(2, all.mix(max = 2).size)
    }
}
