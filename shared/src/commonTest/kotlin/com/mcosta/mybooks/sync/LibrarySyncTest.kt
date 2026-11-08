package com.mcosta.mybooks.sync

import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.FakeBookmarkDao
import com.mcosta.mybooks.data.FakeChallengeDao
import com.mcosta.mybooks.data.FakeEbookDao
import com.mcosta.mybooks.data.FakeQuoteDao
import com.mcosta.mybooks.data.FakeShareStateDao
import com.mcosta.mybooks.data.FakeSyncQueueDao
import com.mcosta.mybooks.data.FakeSyncStateDao
import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.db.BookEntity
import com.mcosta.mybooks.data.db.QuoteEntity
import com.mcosta.mybooks.data.db.ShareStateEntity
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.domain.searchKey
import com.mcosta.mybooks.share.ShareTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OWNER = "owner"

/** A device's link to the server: offline, every call fails with OFFLINE; [afterPush] runs once a push has reached it. */
private class Connection(private val server: FakeSyncServer) : SyncApi by server {
    var online = true
    var afterPush: (suspend () -> Unit)? = null

    private fun check() {
        if (!online) throw SyncException(SyncException.Kind.OFFLINE, "offline")
    }

    override suspend fun push(rows: List<SyncRow>): PushResult {
        check()
        val result = server.push(rows)
        afterPush?.invoke()
        return result
    }

    override suspend fun pull(after: Long, max: Int): PullPage {
        check()
        return server.pull(after, max)
    }
}

/** One phone: its own (fake) database, queue, share state and engine. */
private class Device(server: FakeSyncServer, scope: CoroutineScope, clock: () -> Long, api: SyncApi? = null) {
    val books = FakeBookDao()
    val quotes = FakeQuoteDao()
    val queue = FakeSyncQueueDao()
    // These tests are about the engine once the first sign-in merge is done (FirstMergeTest covers it).
    val state = FakeSyncStateDao().apply { rows["merged:$OWNER"] = "1" }
    val prefValues = mutableMapOf<String, String>()
    val recorder = SyncRecorder(queue, clock, { true }, { it() })
    val syncBooks = SyncingBookDao(books, recorder)
    val net = Connection(server)
    val fs = FakeFileSystem()
    val covers = CoverStore(fs, "/covers".toPath())
    val shareDao = FakeShareStateDao()
    var pendingCalls = 0
    val shares = ShareTracker(shareDao, activeOwner = { OWNER }, onPending = { pendingCalls++ })
    val db = SyncDatabase(
        books = books, quotes = quotes, ebooks = FakeEbookDao(), bookmarks = FakeBookmarkDao(),
        challenges = FakeChallengeDao(), queue = queue, state = state,
        prefs = object : SyncedPrefs {
            override fun get(key: String) = prefValues[key]
            override fun set(key: String, value: String?) {
                if (value == null) prefValues -= key else prefValues[key] = value
            }
        },
    )
    val applier = RemoteApplier(db, recorder, covers, shares)
    val sync = LibrarySync(api ?: net, db, recorder, applier, clock, scope)

    fun book(uuid: String): BookEntity? = books.rows.value.values.firstOrNull { it.uuid == uuid }

    /** A book already on the device before sync (every field timed 0), not queued. */
    suspend fun seed(uuid: String, title: String, cover: String? = null): Long = books.insert(bookEntity(uuid, title, cover))

    /** A local edit, the way the app makes one: stamped and queued. */
    suspend fun edit(uuid: String, change: (BookEntity) -> BookEntity) = syncBooks.update(change(book(uuid)!!))
}

private fun bookEntity(uuid: String, title: String, cover: String? = null): BookEntity {
    val book = BookEntity(
        isbn = null, title = title, authors = "Eça de Queirós", publisher = null, publishedYear = null, pageCount = null,
        coverUrl = cover, description = null, status = ReadingStatus.READING, rating = null, notes = "", dateAdded = 0,
        dateRead = null, searchText = searchKey("$title\nEça de Queirós"), uuid = uuid,
    )
    return book.copy(fieldTimes = timesJson(book.toRow(null).fields.keys.associateWith { 0L }))
}

class LibrarySyncTest {
    /**
     * The engines run on the test scheduler but outside the test's job: advanceUntilIdle() drives them (it skips
     * backgroundScope), and the test doesn't wait for their endless loops to finish.
     */
    private val engineJob = SupervisorJob()
    private val TestScope.engineScope get() = CoroutineScope(StandardTestDispatcher(testScheduler) + engineJob)

    @AfterTest
    fun stopEngines() = engineJob.cancel()

    private fun TestScope.server() = FakeSyncServer(OWNER, clock = { testScheduler.currentTime })
    private fun TestScope.device(server: FakeSyncServer, api: SyncApi? = null) =
        Device(server, engineScope, { testScheduler.currentTime }, api)

    private fun FakeSyncServer.pushTimes() = calls.filter { it.first == "push" }.map { it.second }
    private fun FakeSyncServer.rating(uuid: String) =
        stored()[SyncTable.BOOKS to uuid]?.fields?.get("rating")?.jsonPrimitive?.intOrNull

    @Test
    fun localEditIsPushedAndQueueEmptied() = runTest {
        val server = server()
        val a = device(server)
        a.seed("x", "Os Maias")
        a.sync.start(OWNER)
        advanceUntilIdle()

        a.edit("x") { it.copy(rating = 4) }
        advanceUntilIdle()

        assertEquals(4, server.rating("x"))
        assertTrue(a.queue.rows.value.isEmpty())
        assertIs<SyncStatus.Synced>(a.sync.status.value)
    }

    @Test
    fun offlineKeepsQueueAndRetriesWithBackoff() = runTest {
        val server = server()
        val a = device(server)
        a.seed("x", "Os Maias")
        a.edit("x") { it.copy(rating = 5) }
        server.failNext = SyncException(SyncException.Kind.OFFLINE, "no network")
        server.failTimes = 3

        a.sync.start(OWNER)
        runCurrent()
        assertEquals(SyncStatus.Offline(1), a.sync.status.value)
        assertEquals(1, a.queue.rows.value.size)

        advanceTimeBy(4_999)
        assertEquals(listOf(0L), server.pushTimes())
        advanceUntilIdle()

        // Waits of 5 s, 15 s and 60 s after each failure.
        assertEquals(listOf(0L, 5_000L, 20_000L, 80_000L), server.pushTimes())
        assertEquals(SyncStatus.Synced(80_000L), a.sync.status.value)
        assertTrue(a.queue.rows.value.isEmpty())
        assertEquals(5, server.rating("x"))
    }

    @Test
    fun retryWaitsRepeatTheLastDelay() = runTest {
        val server = server()
        val a = device(server)
        a.seed("x", "Os Maias")
        a.edit("x") { it.copy(rating = 5) }
        server.failNext = SyncException(SyncException.Kind.OTHER, "500")
        server.failTimes = 5

        a.sync.start(OWNER)
        runCurrent()
        assertEquals(SyncStatus.Failed(1), a.sync.status.value)
        advanceUntilIdle()

        assertEquals(listOf(0L, 5_000L, 20_000L, 80_000L, 380_000L, 680_000L), server.pushTimes())
        assertIs<SyncStatus.Synced>(a.sync.status.value)
    }

    @Test
    fun connectivityRestoredRetriesImmediately() = runTest {
        val server = server()
        val a = device(server)
        a.seed("x", "Os Maias")
        a.edit("x") { it.copy(rating = 5) }
        server.failNext = SyncException(SyncException.Kind.OFFLINE, "no network")

        a.sync.start(OWNER)
        runCurrent()
        assertEquals(SyncStatus.Offline(1), a.sync.status.value)

        advanceTimeBy(1_000)
        a.sync.connectivityRestored()
        runCurrent()

        assertEquals(listOf(0L, 1_000L), server.pushTimes())
        assertEquals(SyncStatus.Synced(1_000L), a.sync.status.value)
        assertTrue(a.queue.rows.value.isEmpty())
    }

    @Test
    fun authErrorShowsSignInAgainKeepsQueue() = runTest {
        val server = server()
        val a = device(server)
        a.seed("x", "Os Maias")
        a.edit("x") { it.copy(rating = 5) }
        server.failNext = SyncException(SyncException.Kind.AUTH, "expired")

        a.sync.start(OWNER)
        runCurrent()
        assertEquals(SyncStatus.SignInAgain, a.sync.status.value)
        assertEquals(1, a.queue.rows.value.size)

        // No retry loop, and connectivity doesn't restart it either.
        advanceTimeBy(1_000_000)
        a.sync.connectivityRestored()
        a.edit("x") { it.copy(rating = 4) }
        advanceUntilIdle()
        assertEquals(1, server.calls.size)
        assertEquals(SyncStatus.SignInAgain, a.sync.status.value)

        // Signed in again.
        a.sync.start(OWNER)
        advanceUntilIdle()
        assertIs<SyncStatus.Synced>(a.sync.status.value)
        assertEquals(4, server.rating("x"))
        assertTrue(a.queue.rows.value.isEmpty())
    }

    @Test
    fun setAsideDoesNotBlockQueue() = runTest {
        val server = server()
        val a = device(server)
        for ((uuid, title) in listOf("x" to "Os Maias", "y" to "Mau", "z" to "A Cidade e as Serras")) {
            a.seed(uuid, title)
            a.edit(uuid) { it.copy(rating = 3) }
        }
        server.refuse = { row -> if (row.fields["title"] == JsonPrimitive("Mau")) "title too long" else null }

        a.sync.start(OWNER)
        advanceUntilIdle()

        assertTrue(a.queue.rows.value.isEmpty())
        assertEquals(1, a.sync.setAside.value)
        assertEquals(setOf("x", "z"), server.stored().keys.map { it.second }.toSet())
        assertIs<SyncStatus.Synced>(a.sync.status.value)
    }

    @Test
    fun pushIsSentInBatchesOf200() = runTest {
        val server = server()
        val a = device(server)
        repeat(450) { i ->
            a.seed("b$i", "Livro $i")
            a.edit("b$i") { it.copy(rating = 2) }
        }

        a.sync.start(OWNER)
        advanceUntilIdle()

        assertEquals(3, server.pushTimes().size)
        assertEquals(450, server.stored().size)
        assertTrue(a.queue.rows.value.isEmpty())
    }

    @Test
    fun pullReadsEveryPageAndStoresTheCursor() = runTest {
        val server = server()
        val b = device(server)
        repeat(1_100) { i ->
            b.seed("b$i", "Livro $i")
            b.edit("b$i") { it.copy(rating = 1) }
        }
        b.sync.start(OWNER)
        advanceUntilIdle()

        val pages = mutableListOf<Pair<Long, Int>>()
        val counting = object : SyncApi by server {
            override suspend fun pull(after: Long, max: Int): PullPage =
                server.pull(after, max).also { pages += after to it.rows.size }
        }
        val a = device(server, counting)
        a.sync.start(OWNER)
        advanceUntilIdle()

        assertEquals(1_100, a.books.rows.value.size)
        assertEquals(server.stored().values.maxOf { it.seq }.toString(), a.state.rows["seq"])
        // Pages of 500, 500 and 100, each from where the last one ended; the short one ends the loop.
        assertEquals(listOf(500, 500, 100), pages.take(3).map { it.second })
        assertEquals(0L, pages[0].first)
        assertTrue(pages[1].first > pages[0].first && pages[2].first > pages[1].first)
        assertTrue(pages.drop(3).all { it.second == 0 })
    }

    @Test
    fun twoDevicesConverge() = runTest {
        val server = server()
        val a = device(server)
        val b = device(server)
        for (d in listOf(a, b)) {
            d.seed("x", "Os Maias")
            d.seed("y", "Amor de Perdição")
            d.net.online = false
            d.sync.start(OWNER)
        }
        runCurrent()
        assertIs<SyncStatus.Offline>(a.sync.status.value)

        advanceTimeBy(1_000)
        a.edit("x") { it.copy(rating = 5) }
        advanceTimeBy(100)
        b.edit("x") { it.copy(notes = "nota") }
        advanceTimeBy(100)
        b.edit("y") { it.copy(title = "Amor de Perdição (Camilo)") }

        for (d in listOf(a, b)) {
            d.net.online = true
            d.sync.connectivityRestored()
        }
        advanceUntilIdle()

        for (d in listOf(a, b)) {
            assertEquals(5, d.book("x")!!.rating)
            assertEquals("nota", d.book("x")!!.notes)
            assertEquals("Amor de Perdição (Camilo)", d.book("y")!!.title)
            assertTrue(d.queue.rows.value.isEmpty())
            assertIs<SyncStatus.Synced>(d.sync.status.value)
        }
        assertEquals(a.book("x")!!.fieldTimes, b.book("x")!!.fieldTimes)
    }

    @Test
    fun pingTriggersPull() = runTest {
        val server = server()
        val a = device(server)
        val b = device(server)
        for (d in listOf(a, b)) {
            d.seed("x", "Os Maias")
            d.sync.start(OWNER)
        }
        advanceUntilIdle()

        // Nothing but B's ping tells A: no refresh, no local change, no connectivity event.
        advanceTimeBy(1_000)
        b.edit("x") { it.copy(rating = 3) }
        advanceUntilIdle()

        assertEquals(3, a.book("x")!!.rating)
        assertTrue(a.queue.rows.value.isEmpty())
    }

    @Test
    fun pingResubscriptionPulls() = runTest {
        val server = server()
        var subscriptions = 0
        // The ping channel drops at once and reconnects after the first retry wait (5 s): pings sent meanwhile are lost.
        val flaky = object : SyncApi by server {
            override fun pings(owner: String): Flow<Unit> = flow {
                subscriptions++
                if (subscriptions == 1) throw SyncException(SyncException.Kind.OFFLINE, "socket closed")
                awaitCancellation()
            }
        }
        val a = device(server, flaky)
        val b = device(server)
        for (d in listOf(a, b)) d.seed("x", "Os Maias")
        a.sync.start(OWNER)
        b.sync.start(OWNER)
        runCurrent()
        assertEquals(1, subscriptions)

        advanceTimeBy(1_000)
        b.edit("x") { it.copy(rating = 3) }
        advanceTimeBy(2_000)
        assertNull(a.book("x")!!.rating)

        advanceUntilIdle()
        assertEquals(2, subscriptions)
        assertEquals(3, a.book("x")!!.rating)
    }

    @Test
    fun pulledChangeIsNotPublished() = runTest {
        val server = server()
        val a = device(server)
        val b = device(server)
        for (d in listOf(a, b)) d.seed("x", "Os Maias")
        val bookId = a.book("x")!!.id
        a.shareDao.upsert(
            ShareStateEntity(
                bookId = bookId, shareId = "x", owner = OWNER, hasBefore = false, beforeStatus = null, beforeRating = null,
                beforeProgress = null, beforeReview = null, pending = false, excluded = false, deleted = false,
            )
        )
        for (d in listOf(a, b)) d.sync.start(OWNER)
        advanceUntilIdle()

        advanceTimeBy(1_000)
        b.edit("x") { it.copy(rating = 5, status = ReadingStatus.READ) }
        advanceUntilIdle()

        assertEquals(5, a.book("x")!!.rating)
        assertEquals(0, a.pendingCalls)
        val share = a.shareDao.get(bookId)!!
        assertFalse(share.pending)
        // syncedFromElsewhere: the pulled values are this device's "before" now.
        assertTrue(share.hasBefore)
        assertEquals(5, share.beforeRating)
        assertEquals(ReadingStatus.READ, share.beforeStatus)
    }

    @Test
    fun childBeforeBookIsAppliedLater() = runTest {
        val server = server()
        val bookRow = bookEntity("x", "Os Maias").toRow(null)
        val quoteRow = SyncRow(
            SyncTable.QUOTES, "q1",
            mapOf(
                "book_id" to JsonPrimitive("x"), "text" to JsonPrimitive("Uma frase"), "page" to JsonPrimitive(10),
                "date_added" to JsonPrimitive(5), "position" to JsonNull, "deleted" to JsonPrimitive(false),
            ),
            mapOf("book_id" to 100L, "text" to 100L, "page" to 100L, "date_added" to 100L, "position" to 100L, "deleted" to 100L),
        )
        var bookSent = false
        val pages = object : SyncApi by server {
            override suspend fun pull(after: Long, max: Int): PullPage = when (after) {
                0L -> PullPage(listOf(quoteRow.copy(seq = 1)), 1)
                1L -> if (bookSent) PullPage(listOf(bookRow.copy(seq = 2)), 2) else PullPage(emptyList(), 1)
                else -> PullPage(emptyList(), after)
            }
        }
        val a = device(server, pages)
        a.sync.start(OWNER)
        advanceUntilIdle()
        assertTrue(a.quotes.rows.value.isEmpty())
        assertTrue(a.state.rows.getValue("orphans").contains("\"q1\""))
        assertEquals("1", a.state.rows["seq"])

        bookSent = true
        a.sync.refresh()

        val book = assertNotNull(a.book("x"))
        val quote = assertNotNull(a.quotes.rows.value.values.singleOrNull())
        assertEquals("q1", quote.uuid)
        assertEquals(book.id, quote.bookId)
        assertEquals("Uma frase", quote.text)
        assertEquals("2", a.state.rows["seq"])
        assertEquals("[]", a.state.rows["orphans"])
        assertTrue(a.queue.rows.value.isEmpty())
    }

    @Test
    fun crashBetweenPushAndConfirmResendsSafely() = runTest {
        val server = server()
        val a = device(server)
        a.seed("x", "Os Maias")
        a.edit("x") { it.copy(rating = 5) }
        a.net.afterPush = {
            a.sync.stop()
            awaitCancellation()
        }
        a.sync.start(OWNER)
        advanceUntilIdle()

        assertEquals(1, a.queue.rows.value.size)
        val serverBefore = server.stored().toMap()
        val localBefore = a.book("x")
        assertEquals(5, server.rating("x"))

        a.net.afterPush = null
        a.sync.start(OWNER)
        advanceUntilIdle()

        assertEquals(2, server.pushTimes().size)
        assertEquals(serverBefore, server.stored().toMap())
        assertEquals(localBefore, a.book("x"))
        assertTrue(a.queue.rows.value.isEmpty())
    }

    @Test
    fun editDuringPushIsNotLost() = runTest {
        val server = server()
        val a = device(server)
        a.seed("x", "Os Maias")
        advanceTimeBy(1_000)
        a.edit("x") { it.copy(rating = 5) }
        var once = true
        a.net.afterPush = {
            if (once) {
                once = false
                a.edit("x") { it.copy(notes = "editada durante o envio") }
            }
        }
        a.sync.start(OWNER)
        advanceUntilIdle()

        val stored = server.stored()[SyncTable.BOOKS to "x"]!!
        assertEquals(JsonPrimitive("editada durante o envio"), stored.fields["notes"])
        assertTrue(a.queue.rows.value.isEmpty())
    }

    @Test
    fun remoteDeleteRemovesLocalRow() = runTest {
        val server = server()
        val a = device(server)
        val b = device(server)
        a.covers.put("x.jpg", byteArrayOf(1, 2, 3))
        a.seed("x", "Os Maias", cover = "cover:x.jpg")
        b.seed("x", "Os Maias")
        val aBookId = a.book("x")!!.id
        a.quotes.insert(QuoteEntity(bookId = aBookId, text = "Uma frase", page = 1, dateAdded = 0, uuid = "q1"))
        for (d in listOf(a, b)) d.sync.start(OWNER)
        advanceUntilIdle()

        advanceTimeBy(1_000)
        b.syncBooks.delete(b.book("x")!!.id)
        advanceUntilIdle()

        assertNull(a.book("x"))
        assertNull(a.covers.read("cover:x.jpg"))
        assertTrue(a.quotes.rows.value.isEmpty())
        // The deleting device removes its tombstone once the server has the deletion.
        assertNull(b.book("x"))
        assertTrue(b.queue.rows.value.isEmpty())
    }

    @Test
    fun unconfirmedLocalDeleteIsKeptUntilPushed() = runTest {
        val server = server()
        val a = device(server)
        val b = device(server)
        for (d in listOf(a, b)) d.seed("x", "Os Maias")
        advanceTimeBy(1_000)
        b.edit("x") { it.copy(rating = 2) }
        b.sync.start(OWNER)
        advanceUntilIdle()

        // A deletes, and B's older edit lands before A's deletion is pushed: the tombstone must survive to be pushed.
        advanceTimeBy(1_000)
        a.syncBooks.delete(a.book("x")!!.id)
        a.applier.apply(server.stored().values.toList())
        assertTrue(a.book("x")!!.deleted)
        assertEquals(2, a.book("x")!!.rating)
        assertEquals(1, a.queue.rows.value.size)

        a.sync.start(OWNER)
        advanceUntilIdle()

        assertNull(a.book("x"))
        assertNull(b.book("x"))
        assertEquals(JsonPrimitive(true), server.stored()[SyncTable.BOOKS to "x"]!!.fields["deleted"])
    }

    @Test
    fun keptPhotoKeepsItsFileName() = runTest {
        val server = server()
        val a = device(server)
        val b = device(server)
        a.seed("x", "Os Maias", cover = "cover:random-name.jpg")
        b.seed("x", "Os Maias")
        for (d in listOf(a, b)) d.sync.start(OWNER)
        advanceUntilIdle()

        advanceTimeBy(1_000)
        b.edit("x") { it.copy(rating = 4) }
        advanceUntilIdle()

        assertEquals(4, a.book("x")!!.rating)
        assertEquals("cover:random-name.jpg", a.book("x")!!.coverUrl)
    }

    @Test
    fun prefTravelsBetweenDevices() = runTest {
        val server = server()
        val a = device(server)
        val b = device(server)
        b.prefValues["home.pinnedTags"] = "Velho"
        b.state.rows["prefs/home.pinnedTags"] = "100"
        for (d in listOf(a, b)) d.sync.start(OWNER)
        advanceUntilIdle()

        advanceTimeBy(500)
        a.prefValues["home.pinnedTags"] = "Clássicos\nPoesia"
        a.state.rows["prefs/home.pinnedTags"] = testScheduler.currentTime.toString()
        a.recorder.record(SyncTable.PREFS, "home.pinnedTags")
        advanceUntilIdle()

        assertEquals("Clássicos\nPoesia", b.prefValues["home.pinnedTags"])
        assertEquals(a.state.rows["prefs/home.pinnedTags"], b.state.rows["prefs/home.pinnedTags"])
        assertTrue(b.queue.rows.value.isEmpty())
    }

    @Test
    fun stopTurnsSyncOff() = runTest {
        val server = server()
        val a = device(server)
        a.seed("x", "Os Maias")
        a.sync.start(OWNER)
        advanceUntilIdle()
        a.sync.stop()
        assertEquals(SyncStatus.Off, a.sync.status.value)

        a.edit("x") { it.copy(rating = 1) }
        advanceUntilIdle()
        assertNull(server.rating("x"))
    }

    @Test
    fun statusLabels() {
        val now = 10_000_000L
        assertEquals("Synced just now", SyncStatus.Synced(now - 30_000).label(now))
        assertEquals("Synced 5 minutes ago", SyncStatus.Synced(now - 300_000).label(now))
        assertEquals("Synced 5 minutes ago", SyncStatus.Synced(now - 359_999).label(now))
        assertEquals("Synced 1 minute ago", SyncStatus.Synced(now - 60_000).label(now))
        assertEquals("Offline · 3 changes waiting", SyncStatus.Offline(3).label(now))
        assertEquals("Offline · 1 change waiting", SyncStatus.Offline(1).label(now))
        assertEquals("Syncing…", SyncStatus.Syncing.label(now))
        assertEquals("Couldn't sync · Retry", SyncStatus.Failed(2).label(now))
        assertEquals("Sign in again to sync", SyncStatus.SignInAgain.label(now))
    }
}
