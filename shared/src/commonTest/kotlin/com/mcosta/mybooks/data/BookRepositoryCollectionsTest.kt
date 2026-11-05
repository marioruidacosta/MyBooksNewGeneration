package com.mcosta.mybooks.data

import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.testing.fixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Tags, series and loans in the library: kept tidy, merged on Goodreads import, kept in backups. */
class BookRepositoryCollectionsTest {
    private val dao = FakeBookDao()
    private val repo = BookRepository(dao, object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
    }, clock = { 1000L })

    private suspend fun saved(book: Book) = (repo.save(book) as SaveResult.Saved).id

    @Test
    fun tagsAreTidiedWhenSaved() = runTest {
        val id = saved(Book(title = "Dune", tags = listOf(" Sci-fi ", "sci-fi", "", "Classics")))
        assertEquals(listOf("Sci-fi", "Classics"), repo.getBook(id)!!.tags)
        repo.setTags(id, listOf("Classics", "  gift  from Ana "))
        assertEquals(listOf("Classics", "gift from Ana"), repo.getBook(id)!!.tags)
    }

    @Test
    fun aSeriesNumberNeedsASeries() = runTest {
        val id = saved(Book(title = "Dune", series = "  ", seriesNumber = "1"))
        assertNull(repo.getBook(id)!!.series)
        assertNull(repo.getBook(id)!!.seriesNumber)
        val other = saved(Book(title = "Dune Messiah", series = " Dune ", seriesNumber = "#2"))
        assertEquals("Dune", repo.getBook(other)!!.series)
        assertEquals("2", repo.getBook(other)!!.seriesNumber)
    }

    @Test
    fun lendingAndGettingItBack() = runTest {
        val id = saved(Book(title = "Dune"))
        repo.lend(id, "   ", 5)
        assertNull(repo.getBook(id)!!.lentTo)
        repo.lend(id, " Ana ", 5)
        assertEquals("Ana" to 5L, repo.getBook(id)!!.let { it.lentTo to it.lentOn })
        repo.returned(id)
        assertEquals(null to null, repo.getBook(id)!!.let { it.lentTo to it.lentOn })
    }

    @Test
    fun tagsAreCountedMostUsedFirst() = runTest {
        saved(Book(title = "A", tags = listOf("classics", "gift")))
        saved(Book(title = "B", tags = listOf("Classics")))
        saved(Book(title = "C", tags = listOf("audiobook")))
        assertEquals(
            listOf(TagCount("classics", 2), TagCount("audiobook", 1), TagCount("gift", 1)),
            repo.observeTags().first(),
        )
    }

    /** Re-importing from Goodreads adds its shelves to your tags and fills a missing series, taking nothing away. */
    @Test
    fun goodreadsImportJoinsTagsAndFillsTheSeries() = runTest {
        val dune = saved(Book(title = "Dune", isbn = "9780441013593", authors = "Frank Herbert", tags = listOf("favourites")))
        val emma = saved(Book(title = "Emma", authors = "Jane Austen", tags = listOf("Classics", "gift"), series = "Mine", seriesNumber = "9"))
        repo.importGoodreads(fixture("goodreads_export.csv"))
        repo.getBook(dune)!!.let {
            assertEquals("Dune", it.title)
            assertEquals("Dune" to "1", it.series to it.seriesNumber)
            assertEquals(listOf("favourites"), it.tags)
        }
        repo.getBook(emma)!!.let {
            assertEquals(listOf("Classics", "gift"), it.tags)            // "classics" from Goodreads is the same tag
            assertEquals("Mine" to "9", it.series to it.seriesNumber)    // your series stays
        }
    }

    @Test
    fun backupKeepsTagsSeriesAndLoans() = runTest {
        saved(Book(title = "Dune", tags = listOf("sci-fi"), series = "Dune", seriesNumber = "1", lentTo = "Ana", lentOn = 7))
        val backup = repo.exportJson()
        val other = BookRepository(FakeBookDao(), object : BookLookupService {
            override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
        })
        other.importJson(backup)
        val back = other.observeBooks(null, "").first().single()
        assertEquals(listOf("sci-fi"), back.tags)
        assertEquals("Dune" to "1", back.series to back.seriesNumber)
        assertEquals("Ana" to 7L, back.lentTo to back.lentOn)
    }

    @Test
    fun savingFromTheEditScreenKeepsTagsAndLoan() = runTest {
        val id = saved(Book(title = "Dune", status = ReadingStatus.READ, tags = listOf("sci-fi"), lentTo = "Ana", lentOn = 7))
        repo.save(repo.getBook(id)!!.copy(title = "Dune (new edition)"))
        assertEquals(listOf("sci-fi"), repo.getBook(id)!!.tags)
        assertEquals("Ana", repo.getBook(id)!!.lentTo)
    }
}
