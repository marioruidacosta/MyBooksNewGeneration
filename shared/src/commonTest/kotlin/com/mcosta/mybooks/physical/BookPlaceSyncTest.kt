package com.mcosta.mybooks.physical

import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.FakeSyncQueueDao
import com.mcosta.mybooks.data.SaveResult
import com.mcosta.mybooks.data.backup.BackupSerializer
import com.mcosta.mybooks.data.db.toBook
import com.mcosta.mybooks.data.db.toEntity
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.sync.SHELF_FIELDS
import com.mcosta.mybooks.sync.SyncRecorder
import com.mcosta.mybooks.sync.SyncRow
import com.mcosta.mybooks.sync.SyncTable
import com.mcosta.mybooks.sync.SyncingBookDao
import com.mcosta.mybooks.sync.mergeFields
import com.mcosta.mybooks.sync.stamp
import com.mcosta.mybooks.sync.toBookEntity
import com.mcosta.mybooks.sync.toRow
import com.mcosta.mybooks.sync.withShelfPair
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookPlaceSyncTest {
    private val dao = FakeBookDao()
    private val repo = BookRepository(dao, object : BookLookupService {
        override suspend fun lookup(isbn: String) = LookupResult.NotFound
    })

    private suspend fun saved(book: Book): Long = (repo.save(book) as SaveResult.Saved).id

    private suspend fun placed(id: Long, shelf: String, position: Double) {
        val book = assertNotNull(repo.getBook(id))
        dao.update(book.copy(shelfId = shelf, shelfPosition = position).toEntity().copy(uuid = dao.getById(id)!!.uuid))
    }

    private fun row(shelf: String?, position: Double?, time: Long = 10) = SyncRow(
        SyncTable.BOOKS, "b",
        mapOf<String, JsonElement>(
            "title" to JsonPrimitive("T"),
            "shelf_id" to (shelf?.let(::JsonPrimitive) ?: JsonNull),
            "shelf_position" to (position?.let { JsonPrimitive(it) } ?: JsonNull),
        ),
        mapOf("title" to time, "shelf_id" to time, "shelf_position" to time),
    )

    @Test
    fun bookFieldsRoundTrip() {
        val row = Book(title = "Stoner", shelfId = "s1", shelfPosition = 2.5).toEntity().toRow(null)
        assertEquals(JsonPrimitive("s1"), row.fields["shelf_id"])
        assertEquals(JsonPrimitive(2.5), row.fields["shelf_position"])
        val back = row.toBookEntity(null, null).toBook()
        assertEquals("s1", back.shelfId)
        assertEquals(2.5, back.shelfPosition)
    }

    @Test
    fun changingEitherStampsBoth() {
        val old = mapOf<String, JsonElement>(
            "title" to JsonPrimitive("T"), "shelf_id" to JsonPrimitive("s1"), "shelf_position" to JsonPrimitive(1.0),
        )
        val new = old + ("shelf_position" to JsonPrimitive(2.0))
        val times = stamp(old, new, mapOf("title" to 10L, "shelf_id" to 10L, "shelf_position" to 10L), 100, SHELF_FIELDS)
        assertEquals(100, times["shelf_id"])
        assertEquals(100, times["shelf_position"])
        assertEquals(10, times["title"])
    }

    @Test
    fun aWholeNumberFromTheServerIsNotAChange() = runTest {
        val pulled = row("s1", null).let {
            it.copy(fields = it.fields + ("shelf_position" to JsonPrimitive(3)))
        }
        val entity = pulled.toBookEntity(null, null)
        assertEquals(3.0, entity.shelfPosition)
        assertEquals(JsonPrimitive(3), entity.toRow(null).fields["shelf_position"], "written as the server writes it")

        val recorder = SyncRecorder(FakeSyncQueueDao(), { 1000L }, { true }, { it() })
        val syncing = SyncingBookDao(dao, recorder)
        val id = dao.insert(entity)
        val stored = assertNotNull(dao.getById(id))
        syncing.update(stored)
        assertEquals(stored.fieldTimes, dao.getById(id)!!.fieldTimes)
    }

    @Test
    fun theEditFormKeepsThePlace() = runTest {
        val id = saved(Book(title = "Before"))
        val base = assertNotNull(repo.getBook(id))
        placed(id, "s1", 2.0)
        repo.save(base.copy(title = "After"), base)
        val book = assertNotNull(repo.getBook(id))
        assertEquals("After", book.title)
        assertEquals("s1", book.shelfId)
        assertEquals(2.0, book.shelfPosition)
    }

    @Test
    fun statusAndLoanKeepThePlace() = runTest {
        val id = saved(Book(title = "Stoner"))
        placed(id, "s1", 2.0)
        repo.setStatus(id, ReadingStatus.WISHLIST)
        repo.setStatus(id, ReadingStatus.READ)
        repo.lend(id, "Ana", 5)
        repo.returned(id)
        val book = assertNotNull(repo.getBook(id))
        assertEquals("s1", book.shelfId)
        assertEquals(2.0, book.shelfPosition)
    }

    @Test
    fun backupRoundTrip() {
        val text = BackupSerializer.encode(listOf(Book(title = "Stoner", shelfId = "s1", shelfPosition = 2.5)), 1)
        val back = BackupSerializer.decode(text).single()
        assertEquals("s1", back.shelfId)
        assertEquals(2.5, back.shelfPosition)
        val older = BackupSerializer.decode("""{"version":1,"books":[{"title":"Old"}]}""").single()
        assertNull(older.shelfId)
        assertNull(older.shelfPosition)
    }

    @Test
    fun importingABackupWithoutAPlaceKeepsTheBooksPlace() = runTest {
        val id = saved(Book(isbn = "9780141439587", title = "Emma"))
        placed(id, "s1", 2.0)
        repo.importJson(BackupSerializer.encode(listOf(Book(isbn = "9780141439587", title = "Emma")), 1))
        assertEquals("s1", repo.getBook(id)?.shelfId)
        assertEquals(2.0, repo.getBook(id)?.shelfPosition)
    }

    @Test
    fun importingABackupWithAPlaceRestoresIt() = runTest {
        val id = saved(Book(isbn = "9780141439587", title = "Emma"))
        placed(id, "s1", 2.0)
        repo.importJson(BackupSerializer.encode(listOf(Book(isbn = "9780141439587", title = "Emma", shelfId = "s9", shelfPosition = 7.0)), 1))
        assertEquals("s9", repo.getBook(id)?.shelfId)
        assertEquals(7.0, repo.getBook(id)?.shelfPosition)
    }

    @Test
    fun shelfPairFollowsThePlacedBook() {
        val kept = row(null, null, 20)
        val other = row("s1", 2.0, 10)
        val merged = mergeFields(kept, other).withShelfPair(kept, other)
        assertEquals(JsonPrimitive("s1"), merged.fields["shelf_id"])
        assertEquals(JsonPrimitive(2.0), merged.fields["shelf_position"])
    }

    @Test
    fun shelfPairFromTheOtherBookWinsOnTheServer() {
        val kept = row(null, null, 20)
        val other = row("s1", 2.0, 10)
        val merged = mergeFields(kept, other).withShelfPair(kept, other)
        assertTrue(merged.times.getValue("shelf_id") > 20)
        assertEquals(merged.times["shelf_id"], merged.times["shelf_position"])
    }

    @Test
    fun shelfPairPrefersTheKeptBook() {
        val kept = row("s1", 1.0, 10)
        val other = row("s2", 9.0, 20)
        val merged = mergeFields(kept, other).withShelfPair(kept, other)
        assertEquals(JsonPrimitive("s1"), merged.fields["shelf_id"])
        assertEquals(JsonPrimitive(1.0), merged.fields["shelf_position"])
        assertTrue(merged.times.getValue("shelf_id") > 20, "the kept pair must beat the other's newer time")
    }

    @Test
    fun shelfPairIgnoresAnInvalidShelf() {
        val kept = row("gone", 1.0)
        val other = row("s2", 9.0)
        val merged = mergeFields(kept, other).withShelfPair(kept, other) { it == "s2" }
        assertEquals(JsonPrimitive("s2"), merged.fields["shelf_id"])
        assertEquals(JsonPrimitive(9.0), merged.fields["shelf_position"])
    }

    @Test
    fun olderRowsWithoutTheFieldsStayIntact() {
        val local = row("s1", 2.0)
        val fromOlderApp = SyncRow(SyncTable.BOOKS, "b", mapOf("title" to JsonPrimitive("New")), mapOf("title" to 50L))
        val merged = mergeFields(local, fromOlderApp)
        assertEquals(JsonPrimitive("New"), merged.fields["title"])
        assertEquals(JsonPrimitive("s1"), merged.fields["shelf_id"])
        assertEquals(JsonPrimitive(2.0), merged.fields["shelf_position"])
    }
}
