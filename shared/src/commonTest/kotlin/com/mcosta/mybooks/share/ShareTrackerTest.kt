package com.mcosta.mybooks.share

import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.FakeShareStateDao
import com.mcosta.mybooks.data.SaveResult
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ShareTracker through BookRepository, the way the app uses it. */
class ShareTrackerTest {
    private val shares = FakeShareStateDao()
    private var owner: String? = "ana"
    private var pendingCalls = 0
    private var ids = 0
    private val tracker = ShareTracker(shares, activeOwner = { owner }, onPending = { pendingCalls++ }, newShareId = { "share-${++ids}" })
    private val repo = BookRepository(
        FakeBookDao(),
        object : BookLookupService { override suspend fun lookup(isbn: String) = LookupResult.NotFound },
        clock = { 1000L },
        shareTracker = tracker,
    )

    /** Saves a book while nobody is sharing, so it counts as history from before joining. */
    private suspend fun oldBook(book: Book): Long {
        val saved = owner
        owner = null
        val id = (repo.save(book) as SaveResult.Saved).id
        owner = saved
        return id
    }

    @Test
    fun firstChangeRecordsBeforeState() = runTest {
        val id = oldBook(Book(title = "Dune", status = ReadingStatus.READ))
        repo.setRating(id, 4)
        val row = shares.get(id)!!
        assertEquals("share-1", row.shareId)
        assertEquals("ana", row.owner)
        assertTrue(row.hasBefore)
        assertEquals(ReadingStatus.READ, row.beforeStatus)
        assertNull(row.beforeRating)
        assertTrue(row.pending)
    }

    @Test
    fun beforeKeptWhileStillPending() = runTest {
        val id = oldBook(Book(title = "Dune", status = ReadingStatus.READ))
        repo.setRating(id, 4)
        repo.setRating(id, 5)
        assertNull(shares.get(id)!!.beforeRating)
    }

    @Test
    fun syncedFromElsewhereRecordsBeforeWithoutPublishing() = runTest {
        val id = oldBook(Book(title = "Dune", status = ReadingStatus.READING))
        repo.setRating(id, 4)
        shares.upsert(shares.get(id)!!.copy(pending = false, hasBefore = false))
        val calls = pendingCalls

        tracker.syncedFromElsewhere(Book(id = id, title = "Dune", status = ReadingStatus.READ, rating = 5))

        val row = shares.get(id)!!
        assertFalse(row.pending)
        assertTrue(row.hasBefore)
        assertEquals(ReadingStatus.READ, row.beforeStatus)
        assertEquals(5, row.beforeRating)
        assertEquals(calls, pendingCalls)
    }

    @Test
    fun syncedFromElsewhereKeepsAWaitingChangesBefore() = runTest {
        val id = oldBook(Book(title = "Dune", status = ReadingStatus.READING))
        repo.setRating(id, 4)

        tracker.syncedFromElsewhere(Book(id = id, title = "Dune", status = ReadingStatus.READ, rating = 4))

        val row = shares.get(id)!!
        assertTrue(row.pending)
        assertEquals(ReadingStatus.READING, row.beforeStatus)
        assertNull(row.beforeRating)
    }

    @Test
    fun syncedFromElsewhereLeavesUnsharedBooksAlone() = runTest {
        val id = oldBook(Book(title = "Dune"))
        tracker.syncedFromElsewhere(Book(id = id, title = "Dune", rating = 5))
        assertNull(shares.get(id))
    }

    @Test
    fun newBookHasNoBefore() = runTest {
        val id = (repo.save(Book(title = "Emma", status = ReadingStatus.WISHLIST)) as SaveResult.Saved).id
        val row = shares.get(id)!!
        assertFalse(row.hasBefore)
        assertTrue(row.pending)
    }

    @Test
    fun notesOnlyChangeIgnored() = runTest {
        val id = oldBook(Book(title = "Dune"))
        repo.setNotes(id, "private thoughts")
        assertNull(shares.get(id))
        assertEquals(0, pendingCalls)
    }

    @Test
    fun nothingWhenNoActiveOwner() = runTest {
        owner = null
        val id = (repo.save(Book(title = "Dune")) as SaveResult.Saved).id
        repo.setRating(id, 3)
        assertNull(shares.get(id))
    }

    @Test
    fun excludedBookIgnored() = runTest {
        val id = oldBook(Book(title = "Dune"))
        repo.setShareExcluded(id, true)
        val excludedRow = shares.get(id)!!
        repo.setRating(id, 3)
        assertEquals(excludedRow, shares.get(id))
        assertTrue(repo.observeShareExcluded(id).first())
    }

    @Test
    fun deleteMarksDeletedPending() = runTest {
        val id = oldBook(Book(title = "Dune"))
        repo.setRating(id, 3)
        repo.delete(id)
        val row = shares.get(id)!!
        assertTrue(row.deleted)
        assertTrue(row.pending)
    }

    @Test
    fun deletingUnsharedBookLeavesNoRow() = runTest {
        val id = oldBook(Book(title = "Dune"))
        repo.delete(id)
        assertNull(shares.get(id))
    }

    @Test
    fun rowOfOtherOwnerReplaced() = runTest {
        val id = oldBook(Book(title = "Dune"))
        owner = "bruno"
        repo.setRating(id, 3)
        owner = "ana"
        repo.setRating(id, 4)
        val row = shares.get(id)!!
        assertEquals("ana", row.owner)
        assertNotEquals("share-1", row.shareId)
        assertEquals(3, row.beforeRating)
    }

    @Test
    fun unexcludeWhilePendingKeepsRow() = runTest {
        val id = oldBook(Book(title = "Dune"))
        repo.setRating(id, 3)
        repo.setShareExcluded(id, true)
        repo.setShareExcluded(id, false)
        val row = shares.get(id)!!
        assertFalse(row.excluded)
        assertFalse(row.pending)
        assertEquals("share-1", row.shareId)
    }

    @Test
    fun unexcludeAfterUploadDeletesRow() = runTest {
        val id = oldBook(Book(title = "Dune"))
        repo.setShareExcluded(id, true)
        shares.upsert(shares.get(id)!!.copy(pending = false))   // the Publisher removed it from the server
        repo.setShareExcluded(id, false)
        assertNull(shares.get(id))
        assertFalse(repo.observeShareExcluded(id).first())
    }

    @Test
    fun onPendingCalledOnce() = runTest {
        val id = oldBook(Book(title = "Dune"))
        repo.setRating(id, 3)
        assertEquals(1, pendingCalls)
        repo.setProgressPercent(id, 20)
        assertEquals(2, pendingCalls)
    }

    @Test
    fun reviewChangeTracked() = runTest {
        val id = oldBook(Book(title = "Dune", status = ReadingStatus.READ))
        repo.setReview(id, "Loved it")
        assertEquals("", shares.get(id)!!.beforeReview)
        assertTrue(shares.get(id)!!.pending)
    }

    @Test
    fun publicStateKeepsOnlyWebCovers() {
        val book = Book(id = 1, title = "Dune", coverUrl = "covers/photo-1.jpg", progressPercent = 30, review = "r")
        assertNull(book.publicState("s").coverUrl)
        assertEquals("https://x/y.jpg", book.copy(coverUrl = "https://x/y.jpg").publicState("s").coverUrl)
        assertEquals(30, book.publicState("s").progress)
    }

    @Test
    fun exclusionSurvivesOwnerChange() = runTest {
        val id = oldBook(Book(title = "Private"))
        repo.setShareExcluded(id, true)
        shares.upsert(shares.get(id)!!.copy(pending = false))
        owner = "ana-rejoined"
        repo.setRating(id, 2)
        assertTrue(shares.get(id)!!.excluded)
        assertFalse(shares.get(id)!!.pending)
        assertTrue(repo.observeShareExcluded(id).first())
    }

    @Test
    fun excludeTakesRowOverForCurrentOwner() = runTest {
        val id = oldBook(Book(title = "Dune"))
        owner = "bruno"
        repo.setRating(id, 3)
        owner = "ana"
        repo.setShareExcluded(id, true)
        assertEquals("ana", shares.get(id)!!.owner)
        assertTrue(shares.get(id)!!.excluded)
    }

    @Test
    fun exclusionSurvivesSharingOffAndOn() = runTest {
        val kept = oldBook(Book(title = "Private"))
        val other = oldBook(Book(title = "Public"))
        repo.setShareExcluded(kept, true)
        repo.setRating(other, 3)
        tracker.forgetOwner("ana")
        assertNull(shares.get(other))
        val row = shares.get(kept)!!
        assertTrue(row.excluded)
        assertFalse(row.pending)
        repo.setRating(kept, 4)
        assertFalse(shares.get(kept)!!.pending)
    }

    @Test
    fun publicStateClampsLongText() {
        val book = Book(id = 1, title = "t".repeat(400), authors = "a".repeat(400))
        assertEquals(300, book.publicState("s").title.length)
        assertEquals(300, book.publicState("s").authors.length)
    }
}
