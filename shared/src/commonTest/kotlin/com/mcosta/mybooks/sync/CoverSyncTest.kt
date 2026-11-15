package com.mcosta.mybooks.sync

import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.FakeBookmarkDao
import com.mcosta.mybooks.data.FakeChallengeDao
import com.mcosta.mybooks.data.FakeEbookDao
import com.mcosta.mybooks.data.FakeQuoteDao
import com.mcosta.mybooks.data.FakeSyncQueueDao
import com.mcosta.mybooks.data.FakeSyncStateDao
import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.db.BookEntity
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.domain.searchKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OWNER = "owner"

/** One phone with a cover folder, wired the way the app does (cover store given to the engine). */
private class CoverPhone(server: FakeSyncServer, scope: CoroutineScope, clock: () -> Long) {
    val books = FakeBookDao()
    val queue = FakeSyncQueueDao()
    val state = FakeSyncStateDao().apply { rows["merged:$OWNER"] = "1" }
    val recorder = SyncRecorder(queue, clock, { true }, { it() })
    val syncBooks = SyncingBookDao(books, recorder)
    val fs = FakeFileSystem()
    val covers = CoverStore(fs, "/covers".toPath())
    val db = SyncDatabase(
        books = books, quotes = FakeQuoteDao(), ebooks = FakeEbookDao(), bookmarks = FakeBookmarkDao(),
        challenges = FakeChallengeDao(), queue = queue, state = state,
    )
    val applier = RemoteApplier(db, recorder, covers)
    val sync = LibrarySync(server, db, recorder, applier, clock, scope, coverStore = covers)

    fun book(uuid: String): BookEntity? = books.rows.value.values.firstOrNull { it.uuid == uuid }

    /** A book already here before sync (every field timed 0), not queued. */
    suspend fun seed(uuid: String, cover: String? = null): Long {
        val b = BookEntity(
            isbn = null, title = "Os Maias", authors = "Eça de Queirós", publisher = null, publishedYear = null,
            pageCount = null, coverUrl = cover, description = null, status = ReadingStatus.READING, rating = null,
            notes = "", dateAdded = 0, dateRead = null, searchText = searchKey("Os Maias\nEça de Queirós"), uuid = uuid,
        )
        return books.insert(b.copy(fieldTimes = timesJson(b.toRow(null).fields.keys.associateWith { 0L })))
    }

    suspend fun edit(uuid: String, change: (BookEntity) -> BookEntity) = syncBooks.update(change(book(uuid)!!))
}

class CoverSyncTest {
    private val engineJob = SupervisorJob()
    private val TestScope.engineScope get() = CoroutineScope(StandardTestDispatcher(testScheduler) + engineJob)

    @AfterTest
    fun stopEngines() = engineJob.cancel()

    private fun TestScope.server() = FakeSyncServer(OWNER, clock = { testScheduler.currentTime })
    private fun TestScope.phone(server: FakeSyncServer) = CoverPhone(server, engineScope) { testScheduler.currentTime }
    private fun FakeSyncServer.count(call: String) = calls.count { it.first == call }

    @Test
    fun takenPhotoIsUploadedOnce() = runTest {
        val server = server()
        val a = phone(server)
        a.covers.put("x.jpg", byteArrayOf(1, 2, 3))
        a.seed("x", "cover:x.jpg")
        advanceTimeBy(1_000)
        a.edit("x") { it.copy(rating = 4) }
        a.sync.start(OWNER)
        advanceUntilIdle()

        assertContentEquals(byteArrayOf(1, 2, 3), server.cover("x"))
        assertEquals("x.jpg", a.state.rows["cover:x"])
        a.sync.refresh()
        advanceUntilIdle()
        assertEquals(1, server.count("cover-up"))
    }

    @Test
    fun replacedPhotoIsUploadedAgain() = runTest {
        val server = server()
        val a = phone(server)
        a.covers.put("x.jpg", byteArrayOf(1))
        a.seed("x", "cover:x.jpg")
        advanceTimeBy(1_000)
        a.edit("x") { it.copy(rating = 4) }
        a.sync.start(OWNER)
        advanceUntilIdle()
        assertEquals(1, server.count("cover-up"))

        a.covers.put("y.jpg", byteArrayOf(9, 9))
        advanceTimeBy(1_000)
        a.edit("x") { it.copy(coverUrl = "cover:y.jpg") }
        advanceUntilIdle()

        assertEquals(2, server.count("cover-up"))
        assertContentEquals(byteArrayOf(9, 9), server.cover("x"))
        assertEquals("y.jpg", a.state.rows["cover:x"])
    }

    @Test
    fun pulledPhotoIsDownloadedOnce() = runTest {
        val server = server()
        val a = phone(server)
        val b = phone(server)
        a.covers.put("x.jpg", byteArrayOf(1, 2, 3))
        a.seed("x", "cover:x.jpg")
        advanceTimeBy(1_000)
        a.edit("x") { it.copy(rating = 4) }
        a.sync.start(OWNER)
        advanceUntilIdle()

        b.sync.start(OWNER)
        advanceUntilIdle()
        assertEquals("cover:x.jpg", b.book("x")!!.coverUrl)
        assertContentEquals(byteArrayOf(1, 2, 3), b.covers.read("cover:x.jpg"))

        b.sync.refresh()
        advanceUntilIdle()
        assertEquals(1, server.count("cover-down"))
        // Having fetched it, B doesn't send it back.
        assertEquals(1, server.count("cover-up"))
    }

    @Test
    fun downloadFailureIsRetriedNextPull() = runTest {
        val server = server()
        val a = phone(server)
        val b = phone(server)
        a.covers.put("x.jpg", byteArrayOf(7))
        a.seed("x", "cover:x.jpg")
        advanceTimeBy(1_000)
        a.edit("x") { it.copy(rating = 4) }
        a.sync.start(OWNER)
        advanceUntilIdle()

        server.downloadFail = SyncException(SyncException.Kind.OFFLINE, "offline")
        b.sync.start(OWNER)
        advanceUntilIdle()
        assertNull(b.covers.read("cover:x.jpg"))

        server.downloadFail = null
        b.sync.refresh()
        advanceUntilIdle()
        assertContentEquals(byteArrayOf(7), b.covers.read("cover:x.jpg"))
    }

    @Test
    fun missingCoverIsNotAskedForEveryPull() = runTest {
        val server = server()
        val a = phone(server)
        val b = phone(server)
        // A's upload is refused, so the row says "photo" while the cloud has none.
        server.uploadFail = SyncException(SyncException.Kind.STORAGE_FULL, "full")
        a.covers.put("x.jpg", byteArrayOf(7))
        a.seed("x", "cover:x.jpg")
        advanceTimeBy(1_000)
        a.edit("x") { it.copy(rating = 4) }
        a.sync.start(OWNER)
        advanceUntilIdle()

        b.sync.start(OWNER)
        advanceUntilIdle()
        b.sync.refresh()
        advanceUntilIdle()
        b.sync.refresh()
        advanceUntilIdle()
        assertEquals(1, server.count("cover-down"))
    }

    @Test
    fun storageFullStopsUploadsNotData() = runTest {
        val server = server()
        val a = phone(server)
        server.uploadFail = SyncException(SyncException.Kind.STORAGE_FULL, "full")
        a.covers.put("x.jpg", byteArrayOf(1))
        a.covers.put("z.jpg", byteArrayOf(2))
        a.seed("x", "cover:x.jpg")
        advanceTimeBy(1_000)
        a.edit("x") { it.copy(rating = 4) }
        a.seed("z", "cover:z.jpg")
        a.sync.start(OWNER)
        advanceUntilIdle()

        assertTrue(a.sync.coversFull.value)
        assertEquals(1, server.count("cover-up"))
        assertNull(server.cover("x"))

        advanceTimeBy(1_000)
        a.edit("x") { it.copy(rating = 5) }
        advanceUntilIdle()

        assertEquals(1, server.count("cover-up"))
        assertEquals(5, (server.stored()[SyncTable.BOOKS to "x"]!!.fields["rating"] as JsonPrimitive).intOrNull)
        assertTrue(a.queue.rows.value.isEmpty())
        assertTrue(a.sync.status.value is SyncStatus.Synced)

        // The next start tries again.
        server.uploadFail = null
        a.sync.stop()
        a.sync.start(OWNER)
        advanceUntilIdle()
        assertFalse(a.sync.coversFull.value)
        assertContentEquals(byteArrayOf(1), server.cover("x"))
    }

    @Test
    fun pulledReplacementOfPhotoDeletesTheOldFile() = runTest {
        val server = server()
        val a = phone(server)
        val b = phone(server)
        a.covers.put("x.jpg", byteArrayOf(1))
        a.seed("x", "cover:x.jpg")
        advanceTimeBy(1_000)
        a.edit("x") { it.copy(rating = 4) }
        b.seed("x")
        for (p in listOf(a, b)) p.sync.start(OWNER)
        advanceUntilIdle()

        advanceTimeBy(1_000)
        b.edit("x") { it.copy(coverUrl = "https://example.com/c.jpg") }
        advanceUntilIdle()

        assertEquals("https://example.com/c.jpg", a.book("x")!!.coverUrl)
        assertNull(a.covers.read("cover:x.jpg"))
        assertEquals(emptyList(), a.fs.listOrNull("/covers".toPath()).orEmpty())
    }

    @Test
    fun replacedPhotoReachesOtherDeviceWithRealisticNames() = runTest {
        val server = server()
        val a = phone(server)
        val b = phone(server)
        a.covers.put("9f3a1c2e.jpg", byteArrayOf(1, 1))
        a.seed("x", "cover:9f3a1c2e.jpg")
        advanceTimeBy(1_000)
        a.edit("x") { it.copy(rating = 4) }
        for (p in listOf(a, b)) p.sync.start(OWNER)
        advanceUntilIdle()
        assertContentEquals(byteArrayOf(1, 1), b.covers.read("cover:x.jpg"))

        a.covers.put("c7b2d4e6.jpg", byteArrayOf(2, 2, 2))
        advanceTimeBy(1_000)
        a.edit("x") { it.copy(coverUrl = "cover:c7b2d4e6.jpg") }
        advanceUntilIdle()

        assertContentEquals(byteArrayOf(2, 2, 2), b.covers.read("cover:x.jpg"))
        assertEquals(2, server.count("cover-down"))
    }

    @Test
    fun coverFileWithoutRecordIsRefreshedOnce() = runTest {
        val server = server()
        val a = phone(server)
        val b = phone(server)
        // B has a stale <uuid>.jpg (say a backup restore) and no sync_state about it.
        b.covers.put("x.jpg", byteArrayOf(1))
        b.seed("x", "cover:x.jpg")
        a.covers.put("9f3a1c2e.jpg", byteArrayOf(2, 2))
        a.seed("x")
        advanceTimeBy(1_000)
        a.edit("x") { it.copy(coverUrl = "cover:9f3a1c2e.jpg") }
        a.sync.start(OWNER)
        advanceUntilIdle()

        b.sync.start(OWNER)
        advanceUntilIdle()
        assertContentEquals(byteArrayOf(2, 2), b.covers.read("cover:x.jpg"))
        assertEquals("1000", b.state.rows["cover-got:x"])
        // The stale file was not sent over A's photo.
        assertContentEquals(byteArrayOf(2, 2), server.cover("x"))

        b.sync.refresh()
        advanceUntilIdle()
        assertEquals(1, server.count("cover-down"))
    }
}
