package com.mcosta.mybooks.data.ebooks

import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.SaveResult
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.EbookMetadata
import com.mcosta.mybooks.domain.ReadingStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EbookMatcherTest {
    private val books = BookRepository(FakeBookDao(), object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
    })
    private val matcher = EbookMatcher(books)

    private suspend fun saved(book: Book) = (books.save(book) as SaveResult.Saved).id

    @Test
    fun isbnMatchesFirst() = runTest {
        val id = saved(Book(title = "Os Maias", authors = "Eça de Queirós", isbn = "9789722039994"))
        saved(Book(title = "Another", authors = "X"))
        val match = matcher.match(EbookMetadata("Different Title", listOf("Someone"), "978-972-20-3999-4", null), "x.epub")
        assertEquals(id, assertIs<Match.Existing>(match).book.id)
    }

    /** Also books that have an ISBN: a Gutenberg file has none, the paper copy in the library does. */
    @Test
    fun titleAndFirstAuthorMatch() = runTest {
        val id = saved(Book(title = "Os Maias: Episódios da Vida Romântica", authors = "Eça de Queirós", isbn = "9789722039994"))
        val match = matcher.match(EbookMetadata("OS MAIAS", listOf("Queirós, Eça de", "Translator"), null, "pt"), "maias.epub")
        assertEquals(id, assertIs<Match.Existing>(match).book.id)
    }

    @Test
    fun unknownBecomesNew() = runTest {
        val match = matcher.match(EbookMetadata("A Cidade e as Serras", listOf("Queirós, Eça de", "Helena Cidade Moura"), "9789722036504", "pt"), "c.epub")
        val book = assertIs<Match.New>(match).book
        assertEquals(Book(title = "A Cidade e as Serras", authors = "Eça de Queirós, Helena Cidade Moura", isbn = "9789722036504", status = ReadingStatus.TO_READ), book)
    }

    /** Review focus: a file with no title in it is named after the file. */
    @Test
    fun noTitleUsesFileName() = runTest {
        val book = assertIs<Match.New>(matcher.match(EbookMetadata(null, emptyList(), null, null), "Os_Maias.epub")).book
        assertEquals("Os Maias", book.title)
        assertEquals("", book.authors)
    }

    @Test
    fun anInvalidIsbnIsLeftOut() = runTest {
        val book = assertIs<Match.New>(matcher.match(EbookMetadata("T", emptyList(), "12345", null), "t.pdf")).book
        assertEquals(null, book.isbn)
    }
}
