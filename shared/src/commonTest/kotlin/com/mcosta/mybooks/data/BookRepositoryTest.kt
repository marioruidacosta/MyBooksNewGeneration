package com.mcosta.mybooks.data

import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BookRepositoryTest {
    private val dao = FakeBookDao()
    private var now = 1000L
    private val lookup = object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
    }
    private val repo = BookRepository(dao, lookup, clock = { now })

    private suspend fun saveOk(book: Book): Long = (repo.save(book) as SaveResult.Saved).id
    private suspend fun get(id: Long) = repo.observeBook(id).first()!!

    @Test
    fun saveSetsDateAdded() = runTest {
        val id = saveOk(Book(title = "Dune"))
        assertEquals(1000L, get(id).dateAdded)
    }

    @Test
    fun updateKeepsDateAdded() = runTest {
        val id = saveOk(Book(title = "Dune"))
        now = 5000L
        saveOk(get(id).copy(title = "Dune Messiah"))
        assertEquals(1000L, get(id).dateAdded)
        assertEquals("Dune Messiah", get(id).title)
    }

    @Test
    fun titleIsTrimmed() = runTest {
        val id = saveOk(Book(title = "  Dune  "))
        assertEquals("Dune", get(id).title)
    }

    @Test
    fun blankTitleRejected() = runTest {
        assertEquals(SaveResult.InvalidTitle, repo.save(Book(title = "   ")))
    }

    @Test
    fun reviewOver2000Rejected() = runTest {
        assertEquals(SaveResult.InvalidReview, repo.save(Book(title = "Dune", review = "x".repeat(2001))))
        val id = saveOk(Book(title = "Dune", review = "x".repeat(2000)))
        assertFailsWith<IllegalArgumentException> { repo.setReview(id, "x".repeat(2001)) }
    }

    @Test
    fun setReviewStoresText() = runTest {
        val id = saveOk(Book(title = "Dune"))
        repo.setReview(id, "Worth it")
        assertEquals("Worth it", get(id).review)
    }

    @Test
    fun ratingSixRejected() = runTest {
        assertEquals(SaveResult.InvalidRating, repo.save(Book(title = "Dune", rating = 6)))
        assertEquals(SaveResult.InvalidRating, repo.save(Book(title = "Dune", rating = 0)))
    }

    @Test
    fun invalidIsbnRejected() = runTest {
        assertEquals(SaveResult.InvalidIsbn, repo.save(Book(title = "Dune", isbn = "12345")))
    }

    @Test
    fun blankIsbnStoredAsNull() = runTest {
        val id = saveOk(Book(title = "Dune", isbn = "  "))
        assertNull(get(id).isbn)
    }

    @Test
    fun duplicateIsbnRejected() = runTest {
        saveOk(Book(title = "Dune", isbn = "9780441013593"))
        assertEquals(SaveResult.DuplicateIsbn, repo.save(Book(title = "Other", isbn = "9780441013593")))
    }

    @Test
    fun sameBookResaveKeepsIsbnOk() = runTest {
        val id = saveOk(Book(title = "Dune", isbn = "9780441013593"))
        assertTrue(repo.save(get(id).copy(notes = "x")) is SaveResult.Saved)
    }

    @Test
    fun hyphenatedIsbnNormalizedOnSave() = runTest {
        val id = saveOk(Book(title = "X", isbn = "978-0-306-40615-7"))
        assertEquals("9780306406157", get(id).isbn)
        assertEquals(id, repo.findByIsbn("0306406152")?.id)
    }

    @Test
    fun markReadSetsDateRead() = runTest {
        val id = saveOk(Book(title = "Dune"))
        now = 2000L
        repo.setStatus(id, ReadingStatus.READ)
        assertEquals(2000L, get(id).dateRead)
    }

    @Test
    fun savingAsReadSetsDateRead() = runTest {
        val id = saveOk(Book(title = "Dune", status = ReadingStatus.READ))
        assertEquals(1000L, get(id).dateRead)
    }

    @Test
    fun markReadKeepsExistingDateRead() = runTest {
        val id = saveOk(Book(title = "Dune", status = ReadingStatus.READ, dateRead = 42L))
        now = 2000L
        repo.setStatus(id, ReadingStatus.READ)
        assertEquals(42L, get(id).dateRead)
    }

    @Test
    fun leavingReadClearsDateRead() = runTest {
        val id = saveOk(Book(title = "Dune", status = ReadingStatus.READ))
        repo.setStatus(id, ReadingStatus.WISHLIST)
        assertNull(get(id).dateRead)
    }

    @Test
    fun markReadingSetsDateStarted() = runTest {
        val id = saveOk(Book(title = "Dune"))
        now = 3000L
        repo.setStatus(id, ReadingStatus.READING)
        assertEquals(3000L, get(id).dateStarted)
    }

    @Test
    fun savingAsReadingSetsDateStarted() = runTest {
        val id = saveOk(Book(title = "Dune", status = ReadingStatus.READING))
        assertEquals(1000L, get(id).dateStarted)
    }

    @Test
    fun markReadingKeepsExistingDateStarted() = runTest {
        val id = saveOk(Book(title = "Dune", status = ReadingStatus.READING, dateStarted = 42L))
        now = 3000L
        repo.setStatus(id, ReadingStatus.READING)
        assertEquals(42L, get(id).dateStarted)
    }

    @Test
    fun finishingKeepsDateStartedAndProgress() = runTest {
        val id = saveOk(Book(title = "Dune", pageCount = 200, status = ReadingStatus.READING))
        repo.setProgressPages(id, 50)
        now = 9000L
        repo.setStatus(id, ReadingStatus.READ)
        assertEquals(1000L, get(id).dateStarted)
        assertEquals(9000L, get(id).dateRead)
        assertEquals(50, get(id).currentPage)
    }

    @Test
    fun backToToReadOrToBuyClearsStartAndProgress() = runTest {
        val id = saveOk(Book(title = "Dune", pageCount = 200, status = ReadingStatus.READING))
        repo.setProgressPages(id, 50)
        repo.setStatus(id, ReadingStatus.TO_READ)
        assertNull(get(id).dateStarted)
        assertNull(get(id).currentPage)
        assertNull(get(id).progressPercent)
    }

    @Test
    fun progressByPageComputesPercent() = runTest {
        val id = saveOk(Book(title = "Dune", pageCount = 200, status = ReadingStatus.READING))
        repo.setProgressPages(id, 50)
        assertEquals(50, get(id).currentPage)
        assertEquals(25, get(id).progressPercent)
    }

    @Test
    fun progressByPageWithoutPageCountHasNoPercent() = runTest {
        val id = saveOk(Book(title = "Dune", status = ReadingStatus.READING))
        repo.setProgressPages(id, 50)
        assertEquals(50, get(id).currentPage)
        assertNull(get(id).progressPercent)
    }

    @Test
    fun progressPastLastPageCapsAt100Percent() = runTest {
        val id = saveOk(Book(title = "Dune", pageCount = 200, status = ReadingStatus.READING))
        repo.setProgressPages(id, 230)
        assertEquals(100, get(id).progressPercent)
    }

    @Test
    fun progressByPercentClearsPage() = runTest {
        val id = saveOk(Book(title = "Dune", pageCount = 200, status = ReadingStatus.READING))
        repo.setProgressPages(id, 50)
        repo.setProgressPercent(id, 60)
        assertEquals(60, get(id).progressPercent)
        assertNull(get(id).currentPage)
    }

    @Test
    fun progressPercentOver100Throws() = runTest {
        val id = saveOk(Book(title = "Dune", status = ReadingStatus.READING))
        assertFailsWith<IllegalArgumentException> { repo.setProgressPercent(id, 101) }
    }

    @Test
    fun negativePageThrows() = runTest {
        val id = saveOk(Book(title = "Dune", status = ReadingStatus.READING))
        assertFailsWith<IllegalArgumentException> { repo.setProgressPages(id, -1) }
    }

    @Test
    fun setDateStarted() = runTest {
        val id = saveOk(Book(title = "Dune", status = ReadingStatus.READING))
        repo.setDateStarted(id, 77L)
        assertEquals(77L, get(id).dateStarted)
    }

    @Test
    fun saveCoercesOutOfRangeProgress() = runTest {
        val id = saveOk(Book(title = "Dune", status = ReadingStatus.READING, progressPercent = 140, currentPage = -3))
        assertEquals(100, get(id).progressPercent)
        assertNull(get(id).currentPage)
    }

    @Test
    fun setRatingAndClear() = runTest {
        val id = saveOk(Book(title = "Dune"))
        repo.setRating(id, 4)
        assertEquals(4, get(id).rating)
        repo.setRating(id, null)
        assertNull(get(id).rating)
    }

    @Test
    fun setRatingOutOfRangeThrows() = runTest {
        val id = saveOk(Book(title = "Dune"))
        assertFailsWith<IllegalArgumentException> { repo.setRating(id, 7) }
    }

    @Test
    fun setNotes() = runTest {
        val id = saveOk(Book(title = "Dune"))
        repo.setNotes(id, "loved it")
        assertEquals("loved it", get(id).notes)
    }

    @Test
    fun deleteRemoves() = runTest {
        val id = saveOk(Book(title = "Dune"))
        repo.delete(id)
        assertNull(repo.observeBook(id).first())
    }

    @Test
    fun observeBooksEscapesQuery() = runTest {
        saveOk(Book(title = "100% Wolf"))
        saveOk(Book(title = "Dune", status = ReadingStatus.READ))
        assertEquals(listOf("100% Wolf"), repo.observeBooks(null, "100%").first().map { it.title })
        assertEquals(listOf("Dune"), repo.observeBooks(ReadingStatus.READ, "").first().map { it.title })
    }

    @Test
    fun searchFindsABookByIsbn() = runTest {
        saveOk(Book(title = "A Cidade e as Serras", authors = "Eça de Queirós", isbn = "9789722036504"))
        saveOk(Book(title = "Os Maias", authors = "Eça de Queirós"))
        assertEquals(listOf("A Cidade e as Serras"), repo.observeBooks(null, "972-20-3650-5").first().map { it.title })
        assertEquals(listOf("A Cidade e as Serras"), repo.observeBooks(null, "978-972-20-3650-4").first().map { it.title })
    }

    /** A shelf searched by ISBN: only that shelf's copy. */
    @Test
    fun shelfSearchByIsbn() = runTest {
        saveOk(Book(title = "A Cidade e as Serras", authors = "Eça de Queirós", isbn = "9789722036504", status = ReadingStatus.READ))
        assertEquals(1, repo.observeBooks(ReadingStatus.READ, "972-20-3650-5").first().size)
        assertEquals(0, repo.observeBooks(ReadingStatus.WISHLIST, "972-20-3650-5").first().size)
    }
}
