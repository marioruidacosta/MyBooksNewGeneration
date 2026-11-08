package com.mcosta.mybooks.sync

import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.FakeBookmarkDao
import com.mcosta.mybooks.data.FakeEbookDao
import com.mcosta.mybooks.data.FakeQuoteDao
import com.mcosta.mybooks.data.FakeSyncQueueDao
import com.mcosta.mybooks.data.PinnedTags
import com.mcosta.mybooks.data.SaveResult
import com.mcosta.mybooks.data.db.BookmarkEntity
import com.mcosta.mybooks.data.db.EbookEntity
import com.mcosta.mybooks.data.db.QuoteEntity
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.EbookFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncingDaosTest {
    private val rawBooks = FakeBookDao()
    private val rawQuotes = FakeQuoteDao()
    private val rawBookmarks = FakeBookmarkDao()
    private val rawEbooks = FakeEbookDao()
    private val queue = FakeSyncQueueDao()
    private var now = 1000L
    private var enabled = true
    private val recorder = SyncRecorder(queue, { now }, { enabled }, { it() })
    private val lookup = object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
    }
    private val repo = BookRepository(
        SyncingBookDao(rawBooks, recorder), lookup, clock = { now },
        quotes = SyncingQuoteDao(rawQuotes, recorder),
        ebooks = SyncingEbookDao(rawEbooks, rawBooks, recorder),
        bookmarks = SyncingBookmarkDao(rawBookmarks, recorder),
    )

    private fun queued() = queue.rows.value.map { it.table to it.rowId }
    private fun stored(id: Long) = rawBooks.rows.value.getValue(id)

    private suspend fun saveOk(book: Book): Long = (repo.save(book) as SaveResult.Saved).id

    @Test
    fun insertStampsAllFieldsAndQueues() = runTest {
        val id = saveOk(Book(title = "Dune", authors = "Frank Herbert"))
        val row = stored(id)
        val times = parseTimes(row.fieldTimes)
        assertEquals(1000L, times["title"])
        assertEquals(1000L, times["authors"])
        assertEquals(1000L, times["deleted"])
        assertEquals(listOf("books" to row.uuid), queued())
    }

    @Test
    fun updateStampsOnlyChangedFieldsKeepsUuid() = runTest {
        val id = saveOk(Book(title = "Dune"))
        val uuid = stored(id).uuid
        queue.clear()
        now = 5000L
        repo.setRating(id, 4)
        val row = stored(id)
        assertEquals(uuid, row.uuid)
        val times = parseTimes(row.fieldTimes)
        assertEquals(5000L, times["rating"])
        assertEquals(1000L, times["title"])
        assertEquals(listOf("books" to uuid), queued())
    }

    @Test
    fun repeatedEditsQueueOnce() = runTest {
        val id = saveOk(Book(title = "Dune"))
        queue.clear()
        repo.setRating(id, 4)
        repo.setNotes(id, "x")
        repo.setReview(id, "y")
        assertEquals(listOf("books" to stored(id).uuid), queued())
    }

    @Test
    fun deleteIsSoftWhileSyncing() = runTest {
        val id = saveOk(Book(title = "Dune"))
        rawQuotes.insert(QuoteEntity(bookId = id, text = "q", page = null, dateAdded = 1))
        rawBookmarks.insert(BookmarkEntity(bookId = id, position = "p", label = "l", dateAdded = 1))
        queue.clear()
        now = 7000L
        repo.delete(id)
        assertTrue(repo.observeBooks(null, "").first().isEmpty())
        assertTrue(stored(id).deleted)
        assertEquals(7000L, parseTimes(stored(id).fieldTimes)["deleted"])
        assertTrue(rawQuotes.rows.value.values.single().deleted)
        assertTrue(rawBookmarks.rows.value.values.single().deleted)
        assertEquals(setOf("books", "quotes", "bookmarks"), queue.rows.value.map { it.table }.toSet())
    }

    @Test
    fun reAddingDeletedIsbnRevivesTheRow() = runTest {
        val id = saveOk(Book(title = "Old", isbn = "9789722036504"))
        val uuid = stored(id).uuid
        repo.delete(id)
        now = 9000L
        val result = repo.save(Book(title = "New", isbn = "9789722036504"))
        assertEquals(SaveResult.Saved(id), result)
        val row = stored(id)
        assertFalse(row.deleted)
        assertEquals("New", row.title)
        assertEquals(uuid, row.uuid)
        assertEquals(9000L, parseTimes(row.fieldTimes)["deleted"])
    }

    @Test
    fun signedOutBehavesAsBefore() = runTest {
        enabled = false
        val id = saveOk(Book(title = "Dune"))
        // Times are stamped even signed out, so a later sign-in does not lose these fields.
        assertEquals(1000L, parseTimes(stored(id).fieldTimes)["title"])
        repo.delete(id)
        assertTrue(rawBooks.rows.value.isEmpty())
        assertTrue(queue.rows.value.isEmpty())
    }

    @Test
    fun signedOutReviveStillWorks() = runTest {
        val id = saveOk(Book(title = "Old", isbn = "9789722036504"))
        repo.delete(id)
        enabled = false
        assertEquals(SaveResult.Saved(id), repo.save(Book(title = "New", isbn = "9789722036504")))
        assertFalse(stored(id).deleted)
    }

    @Test
    fun ebookTakesItsBooksUuidAndSurvivesUpdates() = runTest {
        val id = saveOk(Book(title = "Dune"))
        val dao = SyncingEbookDao(rawEbooks, rawBooks, recorder)
        dao.upsert(EbookEntity(bookId = id, format = EbookFormat.EPUB, fileName = "a.epub", size = 1, dateAdded = 1))
        assertEquals(stored(id).uuid, rawEbooks.rows.value.getValue(id).uuid)
        now = 3000L
        dao.setPosition(id, "pos", 3000)
        val row = rawEbooks.rows.value.getValue(id)
        assertEquals(stored(id).uuid, row.uuid)
        assertEquals(3000L, parseTimes(row.fieldTimes)["position"])
        assertEquals(1000L, parseTimes(row.fieldTimes)["file_name"])
        assertTrue(("ebooks" to stored(id).uuid) in queued())
    }

    @Test
    fun updateWithForeignUuidKeepsStoredUuid() = runTest {
        val id = saveOk(Book(title = "Dune"))
        val uuid = stored(id).uuid
        SyncingBookDao(rawBooks, recorder).update(stored(id).copy(uuid = "random", title = "Dune 2"))
        assertEquals(uuid, stored(id).uuid)
    }

    @Test
    fun pinnedTagsChangeQueuesPref() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val saved = mutableMapOf<String, String>()
        val pinned = PinnedTags(
            prefs = null,
            onChanged = { key ->
                recorder.transaction {
                    saved["prefs/$key"] = recorder.now().toString()
                    recorder.record(SyncTable.PREFS, key)
                }
            },
            scope = scope,
        )
        pinned.setPinned("classics", true)
        scope.advanceUntilIdle()
        assertEquals(listOf("prefs" to "home.pinnedTags"), queued())
        assertEquals("1000", saved["prefs/home.pinnedTags"])
    }

    @Test
    fun photoReplacedByPhotoQueuesTheBook() = runTest {
        val id = saveOk(Book(title = "Dune", coverUrl = "cover:a.jpg"))
        queue.clear()
        now = 4000L
        SyncingBookDao(rawBooks, recorder).update(stored(id).copy(coverUrl = "cover:b.jpg"))
        assertEquals(listOf("books" to stored(id).uuid), queued())
        assertEquals(4000L, parseTimes(stored(id).fieldTimes)["cover_url"])
    }

    @Test
    fun editingToADeletedBooksIsbnFreesIt() = runTest {
        val a = saveOk(Book(title = "A", isbn = "9789722036504"))
        val b = saveOk(Book(title = "B"))
        repo.delete(a)
        queue.clear()
        assertEquals(SaveResult.Saved(b), repo.save(repo.getBook(b)!!.copy(isbn = "9789722036504")))
        assertEquals("9789722036504", stored(b).isbn)
        assertEquals(null, stored(a).isbn)
        assertTrue(stored(a).deleted)
        assertTrue(("books" to stored(a).uuid) in queued())
    }

    @Test
    fun changesEmitOnlyAfterTheTransactionCommits() = runTest(UnconfinedTestDispatcher()) {
        var committed = false
        var depth = 0
        val rec = SyncRecorder(queue, { now }, { true }) { block ->
            depth++
            block()
            depth--
            committed = depth == 0
        }
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch { rec.changes.collect { seen += committed } }
        SyncingBookDao(rawBooks, rec).insert(
            com.mcosta.mybooks.data.db.BookEntity(
                isbn = null, title = "T", authors = "", publisher = null, publishedYear = null, pageCount = null, coverUrl = null,
                description = null, status = com.mcosta.mybooks.domain.ReadingStatus.TO_READ, rating = null, notes = "",
                dateAdded = 1, dateRead = null, searchText = "t",
            ),
        )
        assertEquals(listOf(true), seen)
    }

    @Test
    fun aFailingPrefCallbackDoesNotCrash() = runTest {
        val uncaught = mutableListOf<Throwable>()
        val scope = kotlinx.coroutines.CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler) + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> uncaught += e },
        )
        val pinned = PinnedTags(prefs = null, onChanged = { error("sync_state is locked") }, scope = scope)
        pinned.setPinned("classics", true)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("classics"), pinned.tags.value)
        assertTrue(uncaught.isEmpty(), "uncaught: $uncaught")
    }
}
