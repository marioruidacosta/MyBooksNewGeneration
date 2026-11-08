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
import com.mcosta.mybooks.data.db.BookmarkEntity
import com.mcosta.mybooks.data.db.ChallengeEntity
import com.mcosta.mybooks.data.db.EbookEntity
import com.mcosta.mybooks.data.db.QuoteEntity
import com.mcosta.mybooks.data.db.ShareStateEntity
import com.mcosta.mybooks.data.db.toBook
import com.mcosta.mybooks.data.ebooks.EbookFiles
import com.mcosta.mybooks.domain.EbookFormat
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.domain.searchKey
import com.mcosta.mybooks.share.Publisher
import com.mcosta.mybooks.share.ShareTracker
import com.mcosta.mybooks.social.FakeSocial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OWNER = "owner"

/** Fails the [failCall]-th merge_library call (1-based) with OFFLINE; records the ids of every book merged. */
private class FlakyMerge(private val server: FakeSyncServer, private val failCall: Int) : SyncApi by server {
    var mergeCalls = 0
    val merged = mutableListOf<String>()

    override suspend fun mergeLibrary(books: List<SyncRow>): MergeResult {
        if (++mergeCalls == failCall) throw SyncException(SyncException.Kind.OFFLINE, "offline")
        val result = server.mergeLibrary(books)
        merged += books.map { it.id }
        return result
    }
}

/** One phone, signed in for the first time: its library, share state, files and engine. */
private class Phone(api: SyncApi, scope: CoroutineScope, clock: () -> Long) {
    val books = FakeBookDao()
    val quotes = FakeQuoteDao()
    val ebooks = FakeEbookDao()
    val bookmarks = FakeBookmarkDao()
    val challenges = FakeChallengeDao()
    val queue = FakeSyncQueueDao()
    val state = FakeSyncStateDao()
    val shareDao = FakeShareStateDao()
    val fs = FakeFileSystem()
    val covers = CoverStore(fs, "/covers".toPath())
    val ebookFiles = EbookFiles(fs, "/ebooks".toPath())
    val recorder = SyncRecorder(queue, clock, { true }, { it() })
    var pendingCalls = 0
    val shares = ShareTracker(
        shareDao, activeOwner = { OWNER }, onPending = { pendingCalls++ }, uuidOf = { id -> books.getById(id)?.uuid },
    )
    val db = SyncDatabase(
        books = books, quotes = quotes, ebooks = ebooks, bookmarks = bookmarks, challenges = challenges,
        queue = queue, state = state, shareState = shareDao,
    )
    val applier = RemoteApplier(db, recorder, covers, shares)
    val sync = LibrarySync(
        api, db, recorder, applier, clock, scope, shares = shares, coverStore = covers, ebookFiles = ebookFiles,
    )

    fun merge(api: SyncApi) = FirstMerge(api, db, applier, shares, covers, ebookFiles)

    fun live() = books.rows.value.values.filter { !it.deleted }
    fun book(uuid: String): BookEntity? = books.rows.value.values.firstOrNull { it.uuid == uuid }
}

/** A book with every field timed [time]. */
private fun book(uuid: String, title: String, isbn: String? = null, time: Long = 100): BookEntity {
    val b = BookEntity(
        isbn = isbn, title = title, authors = "Eça de Queirós", publisher = null, publishedYear = null, pageCount = null,
        coverUrl = null, description = null, status = ReadingStatus.READ, rating = null, notes = "", dateAdded = 0,
        dateRead = null, searchText = searchKey("$title\nEça de Queirós"), uuid = uuid,
    )
    return b.copy(fieldTimes = timesJson(b.toRow(null).fields.keys.associateWith { time }))
}

private fun BookEntity.retimed(vararg times: Pair<String, Long>): BookEntity =
    copy(fieldTimes = timesJson(parseTimes(fieldTimes) + times))

class FirstMergeTest {
    private val engineJob = SupervisorJob()
    private val TestScope.engineScope get() = CoroutineScope(StandardTestDispatcher(testScheduler) + engineJob)

    @AfterTest
    fun stopEngines() = engineJob.cancel()

    private fun TestScope.server() = FakeSyncServer(OWNER, clock = { testScheduler.currentTime })
    private fun TestScope.phone(api: SyncApi) = Phone(api, engineScope) { testScheduler.currentTime }

    private fun FakeSyncServer.liveBooks() = stored().values.filter {
        it.table == SyncTable.BOOKS && (it.fields["deleted"] as? JsonPrimitive)?.booleanOrNull != true
    }

    @Test
    fun emptyDeviceJustPulls() = runTest {
        val server = server()
        server.push(listOf(book("c", "Os Maias").toRow(null)))
        val phone = phone(server)

        phone.sync.start(OWNER)
        advanceUntilIdle()

        assertTrue(server.calls.none { it.first == "merge" })
        assertEquals(listOf("c"), phone.live().map { it.uuid })
        assertNotNull(phone.state.get("merged:$OWNER"))
        assertIs<SyncStatus.Synced>(phone.sync.status.value)
    }

    @Test
    fun mergeMatchesAndAdoptsCloudIds() = runTest {
        val server = server()
        server.push(listOf(book("cloud", "Os Maias", isbn = "9789720049572", time = 500).copy(rating = 3).toRow(null)))
        val phone = phone(server)
        phone.books.insert(
            book("local", "Os Maias", isbn = "9789720049572").copy(notes = "Lido em 2020").retimed("notes" to 900),
        )

        phone.sync.start(OWNER)
        advanceUntilIdle()

        val local = phone.live().single()
        assertEquals("cloud", local.uuid)
        assertEquals(3, local.rating)
        assertEquals("Lido em 2020", local.notes)
        val cloud = server.liveBooks().single()
        assertEquals("cloud", cloud.id)
        assertEquals("Lido em 2020", cloud.fields["notes"]?.jsonPrimitive?.contentOrNull)
        assertEquals(3, cloud.fields["rating"]?.jsonPrimitive?.intOrNull)
        assertNotNull(phone.state.get("merged:$OWNER"))
        assertNull(phone.mergeProgress())
        assertTrue(phone.queue.rows.value.isEmpty())
    }

    private fun Phone.mergeProgress() = sync.mergeProgress.value

    @Test
    fun interruptedFirstMergeResumesWithoutDuplicates() = runTest {
        val server = server()
        val api = FlakyMerge(server, failCall = 2)
        val phone = phone(api)
        for (n in 1..250) phone.books.insert(book("local-$n", "Livro número $n"))

        assertFailsWith<SyncException> { phone.merge(api).run(OWNER) }
        assertNull(phone.state.get("merged:$OWNER"))
        assertEquals(100, api.merged.size)

        // The app starts again: a new merge picks up where the last one stopped.
        val again = phone.merge(api)
        val seen = mutableListOf<Pair<Int, Int>?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { again.progress.collect { seen += it } }
        again.run(OWNER)

        assertEquals(250, api.merged.size)
        assertEquals(250, api.merged.toSet().size)
        assertEquals(250, phone.live().size)
        assertEquals(250, phone.live().map { it.uuid }.toSet().size)
        assertEquals(250, server.liveBooks().size)
        assertTrue((250 to 250) in seen, "progress: $seen")
        assertEquals(100 to 250, seen.filterNotNull().first())
        assertNull(again.progress.value)
        assertNotNull(phone.state.get("merged:$OWNER"))
    }

    @Test
    fun sharedBookKeepsItsPublicIdThroughMerge() = runTest {
        val server = server()
        server.push(listOf(book("C", "Os Maias", isbn = "9789720049572", time = 500).toRow(null)))
        val phone = phone(server)
        val id = phone.books.insert(book("S", "Os Maias", isbn = "9789720049572"))
        phone.shareDao.upsert(
            ShareStateEntity(
                id, shareId = "S", owner = OWNER, hasBefore = false, beforeStatus = null, beforeRating = null,
                beforeProgress = null, beforeReview = null, pending = false, excluded = false, deleted = false,
            ),
        )

        phone.sync.start(OWNER)
        advanceUntilIdle()

        assertEquals("C", phone.books.getById(id)!!.uuid)
        val moved = phone.shareDao.get(id)!!
        assertEquals("C", moved.shareId)
        assertFalse(moved.deleted)

        // The old public copy goes through the existing unshare path; the book is published under its new id.
        val social = FakeSocial()
        val publisher = Publisher(
            phone.shareDao, loadBook = { phone.books.getById(it)?.toBook() }, activity = social.activityService,
            activeOwner = { OWNER }, scope = backgroundScope,
        )
        publisher.syncNow()
        assertEquals(listOf("S"), social.unpublished)
        assertEquals(listOf("C"), social.published.map { it.first.shareId })
        // Published against its current state: no feed entry for history.
        assertEquals(social.published.single().first, social.published.single().second)
        assertEquals(listOf(id), phone.shareDao.all().map { it.bookId })
    }

    @Test
    fun duplicateLocalBooksAreCombined() = runTest {
        val server = server()
        val phone = phone(server)
        val first = phone.books.insert(book("a", "Os Maias", isbn = "9789720049572").copy(rating = 4).retimed("rating" to 300))
        val second = phone.books.insert(book("b", "Os Maias: episódios").copy(notes = "Releitura").retimed("notes" to 400))
        phone.quotes.insert(QuoteEntity(bookId = second, text = "Tudo isto é triste", page = 12, dateAdded = 0, uuid = "q"))
        phone.bookmarks.insert(BookmarkEntity(bookId = second, position = "p", label = "cap. 3", dateAdded = 0, uuid = "m"))

        phone.sync.start(OWNER)
        advanceUntilIdle()

        val kept = phone.live().single()
        assertEquals(first, kept.id)
        assertEquals(4, kept.rating)
        assertEquals("Releitura", kept.notes)
        assertEquals("9789720049572", kept.isbn)
        assertEquals(first, phone.quotes.getByUuid("q")!!.bookId)
        assertEquals(first, phone.bookmarks.getByUuid("m")!!.bookId)
        val cloudQuote = server.stored()[SyncTable.QUOTES to "q"]!!
        assertEquals(kept.uuid, cloudQuote.fields["book_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1, server.liveBooks().size)
    }

    @Test
    fun setAsideBooksStayAndDoNotBlockTheMerge() = runTest {
        val server = server()
        server.refuse = { row -> if (row.fields["title"] == JsonPrimitive("Mau")) "title too long" else null }
        val phone = phone(server)
        phone.books.insert(book("x", "Os Maias"))
        phone.books.insert(book("y", "Mau"))

        phone.sync.start(OWNER)
        advanceUntilIdle()

        assertEquals(setOf("x", "y"), phone.live().map { it.uuid }.toSet())
        assertEquals(listOf("x"), server.liveBooks().map { it.id })
        assertEquals(1, phone.sync.setAside.value)
        assertNotNull(phone.state.get("merged:$OWNER"))
        assertIs<SyncStatus.Synced>(phone.sync.status.value)
    }

    @Test
    fun childrenChallengesAndEbooksAreUploadedAfterTheMerge() = runTest {
        val server = server()
        server.push(listOf(book("cloud", "Os Maias", isbn = "9789720049572", time = 500).toRow(null)))
        val phone = phone(server)
        val id = phone.books.insert(book("local", "Os Maias", isbn = "9789720049572"))
        phone.quotes.insert(QuoteEntity(bookId = id, text = "Tudo isto é triste", page = 12, dateAdded = 0, uuid = "q"))
        phone.ebooks.upsert(EbookEntity(id, EbookFormat.EPUB, "os-maias.epub", 10, 0, uuid = "local"))
        phone.challenges.upsert(ChallengeEntity(2026, 30, timesJson(mapOf("goal" to 10L, "deleted" to 10L))))

        phone.sync.start(OWNER)
        advanceUntilIdle()

        assertEquals("cloud", phone.ebooks.get(id)!!.uuid)
        assertEquals("cloud", server.stored()[SyncTable.QUOTES to "q"]?.fields?.get("book_id")?.jsonPrimitive?.contentOrNull)
        assertNotNull(server.stored()[SyncTable.EBOOKS to "cloud"])
        assertEquals(30, server.stored()[SyncTable.CHALLENGES to "2026"]?.fields?.get("goal")?.jsonPrimitive?.intOrNull)
        assertTrue(phone.queue.rows.value.isEmpty())
    }

    @Test
    fun newShareUsesBookUuid() = runTest {
        val phone = phone(server())
        val id = phone.books.insert(book("the-uuid", "Os Maias"))
        val before = phone.books.getById(id)!!.toBook()

        phone.shares.changed(before, before.copy(rating = 5))

        assertEquals("the-uuid", phone.shareDao.get(id)!!.shareId)
    }

    @Test
    fun signOutKeepLeavesLibraryClearsQueue() = runTest {
        val server = server()
        val phone = phone(server)
        phone.books.insert(book("x", "Os Maias"))
        phone.sync.start(OWNER)
        advanceUntilIdle()
        phone.queue.enqueue("books", "x", 0)

        phone.sync.signOut(keepLibrary = true)

        assertEquals(listOf("x"), phone.live().map { it.uuid })
        assertTrue(phone.queue.rows.value.isEmpty())
        assertTrue(phone.state.rows.isEmpty())
        assertEquals(SyncStatus.Off, phone.sync.status.value)
    }

    @Test
    fun signOutRemoveErasesLibrary() = runTest {
        val server = server()
        val phone = phone(server)
        val photo = phone.covers.save(byteArrayOf(1, 2, 3))
        val id = phone.books.insert(book("x", "Os Maias").copy(coverUrl = photo))
        phone.quotes.insert(QuoteEntity(bookId = id, text = "Tudo isto é triste", page = 12, dateAdded = 0))
        phone.bookmarks.insert(BookmarkEntity(bookId = id, position = "p", label = "cap. 3", dateAdded = 0))
        phone.ebookFiles.store(id, EbookFormat.EPUB, Buffer().writeUtf8("epub"))
        phone.ebooks.upsert(EbookEntity(id, EbookFormat.EPUB, "os-maias.epub", 4, 0))
        phone.challenges.upsert(ChallengeEntity(2026, 30))
        phone.shareDao.upsert(
            ShareStateEntity(
                id, shareId = "x", owner = OWNER, hasBefore = false, beforeStatus = null, beforeRating = null,
                beforeProgress = null, beforeReview = null, pending = false, excluded = false, deleted = false,
            ),
        )
        phone.sync.start(OWNER)
        advanceUntilIdle()

        phone.sync.signOut(keepLibrary = false)

        assertTrue(phone.books.rows.value.isEmpty())
        assertTrue(phone.quotes.rows.value.isEmpty())
        assertTrue(phone.bookmarks.rows.value.isEmpty())
        assertTrue(phone.ebooks.rows.value.isEmpty())
        assertTrue(phone.challenges.rows.value.isEmpty())
        assertTrue(phone.shareDao.rows.value.isEmpty())
        assertTrue(phone.queue.rows.value.isEmpty())
        assertTrue(phone.state.rows.isEmpty())
        assertFalse(phone.fs.exists(phone.covers.fileFor(photo)!!))
        assertFalse(phone.ebookFiles.exists(id, EbookFormat.EPUB))
        assertEquals(SyncStatus.Off, phone.sync.status.value)
        // The cloud library is untouched.
        assertEquals(1, server.liveBooks().size)
    }
}
