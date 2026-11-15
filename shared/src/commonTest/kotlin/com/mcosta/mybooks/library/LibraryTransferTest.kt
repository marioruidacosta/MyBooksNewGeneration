package com.mcosta.mybooks.library

import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.db.BookDao
import com.mcosta.mybooks.data.db.BookEntity
import com.mcosta.mybooks.data.db.ChallengeEntity
import com.mcosta.mybooks.data.db.EbookEntity
import com.mcosta.mybooks.data.db.QuoteEntity
import com.mcosta.mybooks.data.ebooks.EbookFiles
import com.mcosta.mybooks.domain.EbookFormat
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.sync.SyncDatabase
import com.mcosta.mybooks.sync.SyncRecorder
import com.mcosta.mybooks.sync.SyncTable
import com.mcosta.mybooks.sync.timesJson
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Moving one library's books into another: matched books combined, the rest copied, the source emptied. */
class LibraryTransferTest {
    private val fs = FakeFileSystem()
    private val files = LibraryFiles(fs, "/files".toPath())
    private val transfer = LibraryTransfer()

    /** A library over fake tables; [records] as an account library that queues its changes. */
    private inner class Lib(id: LibraryId, records: Boolean, books: (FakeBookDao) -> BookDao = { it }) {
        val store = FakeLibraryStore()
        private val db = store.db.let {
            SyncDatabase(books(store.books), it.quotes, it.ebooks, it.bookmarks, it.challenges, it.queue, it.state)
        }
        val contents = LibraryContents(
            db, SyncRecorder(store.queue, { 9_000L }, { records }, { it() }),
            CoverStore(fs, files.coversDir(id)), EbookFiles(fs, files.ebooksDir(id)), fs,
        )

        fun queued(table: SyncTable): Set<String> =
            store.queue.rows.value.filter { it.table == table.wire }.mapTo(mutableSetOf()) { it.rowId }
    }

    private fun book(title: String, isbn: String?, notes: String = "", times: Map<String, Long> = emptyMap()) = BookEntity(
        isbn = isbn, title = title, authors = "Eça de Queirós", publisher = null, publishedYear = null, pageCount = null,
        coverUrl = null, description = null, status = ReadingStatus.READ, rating = null, notes = notes, dateAdded = 0,
        dateRead = null, searchText = title.lowercase(), fieldTimes = timesJson(times),
    )

    private val phone = Lib(LibraryId.Local, records = false)

    @Test
    fun addingPhoneBooksMergesSameIsbn() = runTest(timeout = 30.seconds) {
        val account = Lib(LibraryId.Account("ana"), records = true)
        val held = account.store.books.insert(book("Os Maias", "9789720049575", notes = "a", times = mapOf("notes" to 5L)))
        val heldUuid = account.store.books.getById(held)!!.uuid
        val mine = phone.store.books.insert(book("Os Maias", "9789720049575", notes = "b", times = mapOf("notes" to 3L)))
        val quote = phone.store.quotes.insert(QuoteEntity(bookId = mine, text = "Tudo é bom.", page = 12, dateAdded = 1))
        val quoteUuid = phone.store.quotes.rows.value.getValue(quote).uuid

        assertEquals(1, transfer.move(phone.contents, account.contents))

        val books = account.store.live()
        assertEquals(1, books.size)
        assertEquals(heldUuid, books.single().uuid)
        // Both notes kept, the newer first.
        assertEquals("a\n\nb", books.single().notes)
        val moved = account.store.quotes.rows.value.values.single()
        assertEquals(quoteUuid, moved.uuid)
        assertEquals(held, moved.bookId)
        // The combined book and the quote go up with the next sync.
        assertEquals(setOf(heldUuid), account.queued(SyncTable.BOOKS))
        assertEquals(setOf(quoteUuid), account.queued(SyncTable.QUOTES))
        assertTrue(phone.store.empty())
    }

    @Test
    fun titleAndAuthorMatchOnlyWhenASideHasNoIsbn() = runTest(timeout = 30.seconds) {
        val account = Lib(LibraryId.Account("ana"), records = true)
        account.store.books.insert(book("O Primo Basílio", "9789720049001"))
        account.store.books.insert(book("A Relíquia", null))
        phone.store.books.insert(book("O Primo Basílio", "9789720049999"))
        phone.store.books.insert(book("A Relíquia: romance", "9789720049123"))

        transfer.move(phone.contents, account.contents)

        // Two ISBNs are two books; a book without one is the same book as its title and first author. Neither side's
        // fields have times there: a value beats an empty one (merge_library's rule), so the ISBN comes along.
        assertEquals(
            listOf("A Relíquia" to "9789720049123", "O Primo Basílio" to "9789720049001", "O Primo Basílio" to "9789720049999"),
            account.store.live().map { it.title to it.isbn }.sortedBy { "${it.first}${it.second}" },
        )
    }

    @Test
    fun interruptedMoveRepeatsSafely() = runTest(timeout = 30.seconds) {
        var insertsLeft = 3
        val account = Lib(LibraryId.Account("ana"), records = true) { raw ->
            object : BookDao by raw {
                override suspend fun insert(book: BookEntity): Long {
                    check(insertsLeft-- > 0) { "disk full" }
                    return raw.insert(book)
                }
            }
        }
        var deletesLeft = Int.MAX_VALUE
        val source = Lib(LibraryId.Local, records = false) { raw ->
            object : BookDao by raw {
                override suspend fun delete(id: Long) {
                    check(deletesLeft-- > 0) { "disk full" }
                    raw.delete(id)
                }
            }
        }
        val titles = listOf("Os Maias", "A Relíquia", "O Primo Basílio", "O Crime do Padre Amaro", "A Cidade e as Serras")
        val uuids = titles.mapIndexed { i, title ->
            val id = source.store.books.insert(book(title, "978972004900$i"))
            source.store.quotes.insert(QuoteEntity(bookId = id, text = "Quote from $title", page = i, dateAdded = 1))
            source.store.books.getById(id)!!.uuid
        }

        // The account library fails after three books…
        assertFailsWith<IllegalStateException> { transfer.move(source.contents, account.contents) }
        // …then the phone library fails to let go of the next one, after it was written to the account…
        insertsLeft = Int.MAX_VALUE
        deletesLeft = 0
        assertFailsWith<IllegalStateException> { transfer.move(source.contents, account.contents) }
        // …and the third time it all goes through.
        deletesLeft = Int.MAX_VALUE
        transfer.move(source.contents, account.contents)

        assertEquals(uuids.toSet(), account.store.live().mapTo(mutableSetOf()) { it.uuid })
        assertEquals(titles.size, account.store.books.rows.value.size)
        val quotes = account.store.quotes.rows.value.values
        assertEquals(titles.map { "Quote from $it" }.toSet(), quotes.mapTo(mutableSetOf()) { it.text })
        assertEquals(titles.size, quotes.size)
        assertEquals(uuids.toSet(), account.queued(SyncTable.BOOKS))
        assertTrue(source.store.empty())
    }

    @Test
    fun theTargetsOwnGoalWins() = runTest(timeout = 30.seconds) {
        val account = Lib(LibraryId.Account("ana"), records = true)
        account.store.challenges.upsert(ChallengeEntity(2026, 30))
        phone.store.challenges.upsert(ChallengeEntity(2026, 20))
        phone.store.challenges.upsert(ChallengeEntity(2025, 12))

        transfer.move(phone.contents, account.contents)

        assertEquals(mapOf(2026 to 30, 2025 to 12), account.store.challenges.rows.value.mapValues { it.value.goal })
        assertEquals(setOf("2025"), account.queued(SyncTable.CHALLENGES))
        assertTrue(phone.store.empty())
    }

    @Test
    fun anEbookFileFollowsItsBookToItsNewId() = runTest(timeout = 30.seconds) {
        val account = Lib(LibraryId.Account("ana"), records = true)
        // The account's library already used the phone book's id for another book.
        account.store.books.insert(book("Mensagem", "9789720049100"))
        val mine = phone.store.books.insert(book("Os Maias", "9789720049575"))
        phone.store.ebooks.upsert(EbookEntity(mine, EbookFormat.EPUB, "os-maias.epub", 7, 1))
        val file = phone.contents.ebookFiles.path(mine, EbookFormat.EPUB)
        fs.createDirectories(file.parent!!)
        fs.write(file) { writeUtf8("epub") }

        transfer.move(phone.contents, account.contents)

        val moved = account.store.live().single { it.title == "Os Maias" }
        assertEquals(moved.uuid, account.store.ebooks.rows.value.getValue(moved.id).uuid)
        assertEquals("epub", fs.read(account.contents.ebookFiles.path(moved.id, EbookFormat.EPUB)) { readUtf8() })
        assertTrue(!fs.exists(file))
    }

    @Test
    fun movingIntoThePhoneLibraryQueuesNothing() = runTest(timeout = 30.seconds) {
        val account = Lib(LibraryId.Account("ana"), records = true)
        val id = account.store.books.insert(book("Os Maias", "9789720049575"))
        account.store.quotes.insert(QuoteEntity(bookId = id, text = "Tudo é bom.", page = 12, dateAdded = 1))
        account.store.challenges.upsert(ChallengeEntity(2026, 30))

        transfer.move(account.contents, phone.contents)

        assertEquals(listOf("Os Maias"), phone.store.live().map { it.title })
        assertEquals(1, phone.store.quotes.rows.value.size)
        assertTrue(phone.store.queue.rows.value.isEmpty())
        assertTrue(account.store.empty())
    }
}
