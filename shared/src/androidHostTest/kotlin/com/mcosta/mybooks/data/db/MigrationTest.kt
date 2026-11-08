package com.mcosta.mybooks.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.domain.EbookFormat
import com.mcosta.mybooks.domain.ReadingStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Upgrading the database installed on the phone (version 1) must keep every book. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test.db"

    @Before
    fun createVersion1Database() {
        context.deleteDatabase(name)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        // Exactly the version-1 schema Room generated for the released app.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `books` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `isbn` TEXT, " +
                "`title` TEXT NOT NULL, `authors` TEXT NOT NULL, `publisher` TEXT, `publishedYear` INTEGER, " +
                "`pageCount` INTEGER, `coverUrl` TEXT, `description` TEXT, `status` TEXT NOT NULL, `rating` INTEGER, " +
                "`notes` TEXT NOT NULL, `dateAdded` INTEGER NOT NULL, `dateRead` INTEGER, `searchText` TEXT NOT NULL)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_books_isbn` ON `books` (`isbn`)")
        db.execSQL(
            "INSERT INTO books (isbn, title, authors, coverUrl, status, rating, notes, dateAdded, dateRead, searchText) " +
                "VALUES ('9789726084884', 'A violência e o escárnio', 'Albert Cossery', " +
                "'https://covers.openlibrary.org/b/isbn/9789726084884-L.jpg', 'WISHLIST', 4, 'gift idea', 10, NULL, " +
                "'a violencia e o escarnio\nalbert cossery')"
        )
        db.execSQL(
            "INSERT INTO books (isbn, title, authors, coverUrl, status, rating, notes, dateAdded, dateRead, searchText) " +
                "VALUES ('9780441013593', 'Dune', 'Frank Herbert', " +
                "'https://books.google.com/books/content?id=B1hSG45JCX4C&zoom=1', 'READ', 5, '', 20, 30, 'dune\nfrank herbert')"
        )
        db.version = 1
        db.close()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun version1BooksSurviveUpgrade() = runTest {
        val db = createAndroidDatabase(context, name)
        val books = db.bookDao().getAll().map { it.toBook() }.associateBy { it.title }
        db.close()

        val cossery = books.getValue("A violência e o escárnio")
        assertEquals(ReadingStatus.WISHLIST, cossery.status)
        assertEquals(4, cossery.rating)
        assertEquals("gift idea", cossery.notes)
        assertNull(cossery.dateStarted)
        assertNull(cossery.currentPage)
        assertNull(cossery.progressPercent)

        val dune = books.getValue("Dune")
        assertEquals(30L, dune.dateRead)
        assertEquals("https://books.google.com/books/content?id=B1hSG45JCX4C&zoom=1", dune.coverUrl)
    }

    /** Old cover-by-ISBN links return a blank 1x1 GIF when there's no cover; ask for a 404 instead. */
    @Test
    fun openLibraryIsbnCoverLinksAskForMissingCoverError() = runTest {
        val db = createAndroidDatabase(context, name)
        val cossery = db.bookDao().findByIsbn("9789726084884")!!
        db.close()
        assertEquals("https://covers.openlibrary.org/b/isbn/9789726084884-L.jpg?default=false", cossery.coverUrl)
    }

    /** Version 2 is what phones run before social part 2: books gain an empty review, share_state starts empty. */
    @Test
    fun upgradeFrom2KeepsBooksAndAddsReview() = runTest {
        context.deleteDatabase(name)
        val v2 = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        v2.execSQL(
            "CREATE TABLE IF NOT EXISTS `books` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `isbn` TEXT, " +
                "`title` TEXT NOT NULL, `authors` TEXT NOT NULL, `publisher` TEXT, `publishedYear` INTEGER, " +
                "`pageCount` INTEGER, `coverUrl` TEXT, `description` TEXT, `status` TEXT NOT NULL, `rating` INTEGER, " +
                "`notes` TEXT NOT NULL, `dateAdded` INTEGER NOT NULL, `dateRead` INTEGER, `searchText` TEXT NOT NULL, " +
                "`dateStarted` INTEGER, `currentPage` INTEGER, `progressPercent` INTEGER)"
        )
        v2.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_books_isbn` ON `books` (`isbn`)")
        v2.execSQL(
            "INSERT INTO books (isbn, title, authors, status, rating, notes, dateAdded, searchText, dateStarted, progressPercent) " +
                "VALUES ('9780441013593', 'Dune', 'Frank Herbert', 'READING', 5, 'mine', 20, 'dune\nfrank herbert', 15, 40)"
        )
        v2.version = 2
        v2.close()

        val db = createAndroidDatabase(context, name)
        val dune = db.bookDao().findByIsbn("9780441013593")!!.toBook()
        assertEquals("", dune.review)
        assertEquals(40, dune.progressPercent)
        assertEquals("mine", dune.notes)
        assertTrue(db.shareStateDao().all().isEmpty())
        val row = ShareStateEntity(
            bookId = dune.id, shareId = "s1", owner = "me", hasBefore = true, beforeStatus = ReadingStatus.READ,
            beforeRating = 5, beforeProgress = null, beforeReview = "", pending = true, excluded = false, deleted = false,
        )
        db.shareStateDao().upsert(row)
        assertEquals(row, db.shareStateDao().get(dune.id))
        db.close()
    }

    /** Version 7 adds ebooks and bookmarks, and a position on quotes (for highlights); old quotes have none. */
    @Test
    fun upgradeTo7AddsEbooksBookmarksAndQuotePositions() = runTest {
        val db = createAndroidDatabase(context, name)
        assertTrue(db.ebookDao().getAll().isEmpty())
        assertTrue(db.bookmarkDao().getAll().isEmpty())
        val dune = db.bookDao().findByIsbn("9780441013593")!!
        db.ebookDao().upsert(EbookEntity(dune.id, EbookFormat.EPUB, "dune.epub", 1234, dateAdded = 5))
        db.ebookDao().setPosition(dune.id, "{\"href\":\"ch2.xhtml\"}", lastOpened = 9)
        val stored = db.ebookDao().get(dune.id)!!
        assertEquals(EbookEntity(dune.id, EbookFormat.EPUB, "dune.epub", 1234, 5, "{\"href\":\"ch2.xhtml\"}", 9, uuid = stored.uuid), stored)
        val id = db.bookmarkDao().insert(BookmarkEntity(bookId = dune.id, position = "{\"href\":\"ch1.xhtml\"}", label = "Chapter 1", dateAdded = 6))
        assertEquals(listOf(id), db.bookmarkDao().forBook(dune.id).map { it.id })

        val old = db.quoteDao().insert(QuoteEntity(bookId = dune.id, text = "Fear is the mind-killer.", page = 8, dateAdded = 1))
        val highlight = db.quoteDao().insert(QuoteEntity(bookId = dune.id, text = "The spice must flow.", page = null, dateAdded = 2, position = "{\"href\":\"ch1.xhtml\"}"))
        val quotes = db.quoteDao().forBook(dune.id).associateBy { it.id }
        assertNull(quotes.getValue(old).position)
        assertEquals("{\"href\":\"ch1.xhtml\"}", quotes.getValue(highlight).position)
        db.close()
    }

    /** Version 6 adds quotes: the library survives and the new table starts empty and works. */
    @Test
    fun upgradeAddsEmptyQuotes() = runTest {
        val db = createAndroidDatabase(context, name)
        assertEquals(2, db.bookDao().getAll().size)
        assertTrue(db.quoteDao().getAll().isEmpty())
        val dune = db.bookDao().findByIsbn("9780441013593")!!
        db.quoteDao().insert(QuoteEntity(bookId = dune.id, text = "Fear is the mind-killer.", page = 8, dateAdded = 1))
        db.quoteDao().insert(QuoteEntity(bookId = dune.id, text = "The spice must flow.", page = null, dateAdded = 2))
        db.quoteDao().insert(QuoteEntity(bookId = dune.id, text = "Opening", page = 1, dateAdded = 3))
        assertEquals(listOf("Opening", "Fear is the mind-killer.", "The spice must flow."), db.quoteDao().forBook(dune.id).map { it.text })
        db.quoteDao().deleteForBook(dune.id)
        assertTrue(db.quoteDao().getAll().isEmpty())
        db.close()
    }

    /**
     * Version 5 adds tags, series and loans. A title imported from Goodreads with its series written in has the
     * series moved out (and is found by its new title); other titles stay as they were.
     */
    @Test
    fun upgradeTo5MovesSeriesOutOfTitles() = runTest {
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
            old.execSQL(
                "INSERT INTO books (isbn, title, authors, status, notes, dateAdded, searchText) VALUES " +
                    "(NULL, 'The Hobbit (Middle-earth Universe, #0.5)', 'J.R.R. Tolkien', 'READ', '', 40, " +
                    "'the hobbit (middle-earth universe, #0.5)\nj.r.r. tolkien'), " +
                    "(NULL, 'Ensaio sobre a Cegueira (Edição Especial)', 'José Saramago', 'TO_READ', '', 50, 'x')"
            )
        }
        val db = createAndroidDatabase(context, name)
        val books = db.bookDao().getAll().map { it.toBook() }.associateBy { it.authors }
        val hobbit = books.getValue("J.R.R. Tolkien")
        assertEquals("The Hobbit", hobbit.title)
        assertEquals("Middle-earth Universe", hobbit.series)
        assertEquals("0.5", hobbit.seriesNumber)
        assertEquals("the hobbit\nj.r.r. tolkien", db.bookDao().getById(hobbit.id)!!.searchText)
        assertEquals("Ensaio sobre a Cegueira (Edição Especial)", books.getValue("José Saramago").title)
        assertNull(books.getValue("José Saramago").series)
        assertEquals(listOf<String>(), books.getValue("Frank Herbert").tags)

        db.bookDao().update(books.getValue("Frank Herbert").copy(tags = listOf("Sci-fi", "gift from Ana"), lentTo = "Ana", lentOn = 7).toEntity())
        val dune = db.bookDao().findByIsbn("9780441013593")!!.toBook()
        assertEquals(listOf("Sci-fi", "gift from Ana"), dune.tags)
        assertEquals("Ana", dune.lentTo)
        assertEquals(7L, dune.lentOn)
        db.close()
    }

    /** Version 4 adds reading challenges: the library survives and the new table starts empty and works. */
    @Test
    fun upgradeAddsEmptyChallenges() = runTest {
        val db = createAndroidDatabase(context, name)
        assertEquals(2, db.bookDao().getAll().size)
        assertTrue(db.challengeDao().getAll().isEmpty())
        db.challengeDao().upsert(ChallengeEntity(2026, 30))
        db.challengeDao().upsert(ChallengeEntity(2025, 25))
        db.challengeDao().upsert(ChallengeEntity(2026, 35))
        assertEquals(listOf(ChallengeEntity(2026, 35), ChallengeEntity(2025, 25)), db.challengeDao().getAll())
        db.challengeDao().delete(2025)
        assertEquals(listOf(ChallengeEntity(2026, 35)), db.challengeDao().getAll())
        db.close()
    }

    private val shareId = "1f0e5c1a-7b2d-4c3e-9a41-0d6f8e2b3c4d"

    /** A database exactly as version 7 left it (schemas/…/7.json), filled by [fill]. */
    private fun createVersion7Database(fill: SQLiteDatabase.() -> Unit) {
        context.deleteDatabase(name)
        val v7 = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        v7.execSQL(
            "CREATE TABLE IF NOT EXISTS `books` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `isbn` TEXT, " +
                "`title` TEXT NOT NULL, `authors` TEXT NOT NULL, `publisher` TEXT, `publishedYear` INTEGER, " +
                "`pageCount` INTEGER, `coverUrl` TEXT, `description` TEXT, `status` TEXT NOT NULL, `rating` INTEGER, " +
                "`notes` TEXT NOT NULL, `dateAdded` INTEGER NOT NULL, `dateRead` INTEGER, `searchText` TEXT NOT NULL, " +
                "`dateStarted` INTEGER, `currentPage` INTEGER, `progressPercent` INTEGER, `review` TEXT NOT NULL DEFAULT '', " +
                "`tags` TEXT NOT NULL DEFAULT '', `series` TEXT, `seriesNumber` TEXT, `lentTo` TEXT, `lentOn` INTEGER)"
        )
        v7.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_books_isbn` ON `books` (`isbn`)")
        v7.execSQL(
            "CREATE TABLE IF NOT EXISTS `share_state` (`bookId` INTEGER NOT NULL, `shareId` TEXT NOT NULL, " +
                "`owner` TEXT NOT NULL, `hasBefore` INTEGER NOT NULL, `beforeStatus` TEXT, `beforeRating` INTEGER, " +
                "`beforeProgress` INTEGER, `beforeReview` TEXT, `pending` INTEGER NOT NULL, `excluded` INTEGER NOT NULL, " +
                "`deleted` INTEGER NOT NULL, PRIMARY KEY(`bookId`))"
        )
        v7.execSQL("CREATE TABLE IF NOT EXISTS `challenges` (`year` INTEGER NOT NULL, `goal` INTEGER NOT NULL, PRIMARY KEY(`year`))")
        v7.execSQL(
            "CREATE TABLE IF NOT EXISTS `quotes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, " +
                "`text` TEXT NOT NULL, `page` INTEGER, `dateAdded` INTEGER NOT NULL, `position` TEXT)"
        )
        v7.execSQL("CREATE INDEX IF NOT EXISTS `index_quotes_bookId` ON `quotes` (`bookId`)")
        v7.execSQL(
            "CREATE TABLE IF NOT EXISTS `ebooks` (`bookId` INTEGER NOT NULL, `format` TEXT NOT NULL, `fileName` TEXT NOT NULL, " +
                "`size` INTEGER NOT NULL, `dateAdded` INTEGER NOT NULL, `position` TEXT, `lastOpened` INTEGER, PRIMARY KEY(`bookId`))"
        )
        v7.execSQL(
            "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, " +
                "`position` TEXT NOT NULL, `label` TEXT NOT NULL, `dateAdded` INTEGER NOT NULL)"
        )
        v7.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)")
        v7.fill()
        v7.version = 7
        v7.close()
    }

    /** Version 8 adds sync columns (uuid, fieldTimes, deleted) and the sync tables; nothing is lost, every row gets a uuid. */
    @Test
    fun migrate7To8KeepsRowsAndGivesUuids() = runTest {
        createVersion7Database {
            execSQL(
                "INSERT INTO books (id, isbn, title, authors, publisher, publishedYear, pageCount, coverUrl, description, status, " +
                    "rating, notes, dateAdded, dateRead, searchText, dateStarted, currentPage, progressPercent, review, tags, " +
                    "series, seriesNumber, lentTo, lentOn) VALUES " +
                    "(1, '9780441013593', 'Dune', 'Frank Herbert', 'Chilton', 1965, 412, 'http://c/dune.jpg', 'Desert planet', 'READ', 5, " +
                    "'mine', 20, 30, 'dune\nfrank herbert', 15, 400, 97, 'Great', 'Sci-fi\ngift', 'Dune Chronicles', '1', 'Ana', 7), " +
                    "(2, NULL, 'Emma', 'Jane Austen', NULL, NULL, NULL, NULL, NULL, 'TO_READ', NULL, '', 21, NULL, 'emma\njane austen', " +
                    "NULL, NULL, NULL, '', '', NULL, NULL, NULL, NULL), " +
                    "(3, '9789726084884', 'Shared', 'Someone', 'Pub', 2001, 100, NULL, 'Shared desc', 'READING', 3, 'n', 22, NULL, " +
                    "'shared\nsomeone', 25, 50, 50, 'ok', 'tag', NULL, NULL, NULL, NULL)"
            )
            execSQL("INSERT INTO share_state VALUES (3, '$shareId', 'user1', 0, NULL, NULL, NULL, NULL, 0, 0, 0)")
            execSQL(
                "INSERT INTO quotes (id, bookId, text, page, dateAdded, position) VALUES " +
                    "(1, 1, 'Fear', 8, 1, NULL), (2, 1, 'Spice', NULL, 2, '{\"href\":\"c1.xhtml\"}')"
            )
            execSQL("INSERT INTO ebooks VALUES (1, 'EPUB', 'dune.epub', 1234, 5, '{\"href\":\"c2.xhtml\"}', 9)")
            execSQL("INSERT INTO bookmarks (id, bookId, position, label, dateAdded) VALUES (1, 1, '{\"href\":\"c1.xhtml\"}', 'Ch1', 6)")
            execSQL("INSERT INTO challenges VALUES (2026, 30)")
        }

        val db = createAndroidDatabase(context, name)
        val books = db.bookDao().getAll().associateBy { it.title }
        assertEquals(3, books.size)
        val dune = books.getValue("Dune")
        val emma = books.getValue("Emma")
        val shared = books.getValue("Shared")
        // Every v7 field unchanged; only uuid, fieldTimes and deleted are new.
        val blank = BookEntity(
            isbn = null, title = "", authors = "", publisher = null, publishedYear = null, pageCount = null, coverUrl = null,
            description = null, status = ReadingStatus.TO_READ, rating = null, notes = "", dateAdded = 0, dateRead = null, searchText = "",
        )
        assertEquals(
            blank.copy(
                id = 1, isbn = "9780441013593", title = "Dune", authors = "Frank Herbert", publisher = "Chilton", publishedYear = 1965,
                pageCount = 412, coverUrl = "http://c/dune.jpg", description = "Desert planet", status = ReadingStatus.READ, rating = 5,
                notes = "mine", dateAdded = 20, dateRead = 30, searchText = "dune\nfrank herbert", dateStarted = 15, currentPage = 400,
                progressPercent = 97, review = "Great", tags = "Sci-fi\ngift", series = "Dune Chronicles", seriesNumber = "1",
                lentTo = "Ana", lentOn = 7, uuid = dune.uuid,
            ),
            dune,
        )
        assertEquals(
            blank.copy(id = 2, title = "Emma", authors = "Jane Austen", dateAdded = 21, searchText = "emma\njane austen", uuid = emma.uuid),
            emma,
        )
        assertEquals(
            blank.copy(
                id = 3, isbn = "9789726084884", title = "Shared", authors = "Someone", publisher = "Pub", publishedYear = 2001,
                pageCount = 100, description = "Shared desc", status = ReadingStatus.READING, rating = 3, notes = "n", dateAdded = 22,
                searchText = "shared\nsomeone", dateStarted = 25, currentPage = 50, progressPercent = 50, review = "ok", tags = "tag",
                uuid = shareId,
            ),
            shared,
        )

        val quotes = db.quoteDao().getAll().sortedBy { it.id }
        assertEquals(
            listOf(
                QuoteEntity(1, 1, "Fear", 8, 1, null, uuid = quotes[0].uuid),
                QuoteEntity(2, 1, "Spice", null, 2, "{\"href\":\"c1.xhtml\"}", uuid = quotes[1].uuid),
            ),
            quotes,
        )
        val ebook = db.ebookDao().getAll().single()
        assertEquals(EbookEntity(1, EbookFormat.EPUB, "dune.epub", 1234, 5, "{\"href\":\"c2.xhtml\"}", 9, uuid = dune.uuid), ebook)
        val bookmark = db.bookmarkDao().getAll().single()
        assertEquals(BookmarkEntity(1, 1, "{\"href\":\"c1.xhtml\"}", "Ch1", 6, uuid = bookmark.uuid), bookmark)
        assertEquals(listOf(ChallengeEntity(2026, 30)), db.challengeDao().getAll())

        // Emma is neither shared nor an ebook owner: she gets her own random uuid.
        assertTrue(emma.uuid != shared.uuid && emma.uuid != dune.uuid && dune.uuid != shared.uuid)
        val uuids = books.values.map { it.uuid } + quotes.map { it.uuid } + bookmark.uuid
        val v4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        assertTrue((uuids - shareId).all { v4.matches(it) })
        assertEquals(uuids.size, uuids.toSet().size)
        assertEquals(dune.uuid, ebook.uuid)
        assertNull(db.syncStateDao().get("anything"))
        assertTrue(db.syncQueueDao().batch(10).isEmpty())
        db.close()
    }

    /** An ebook row whose book is gone must not stop the migration (and so the app from opening). */
    @Test
    fun migrate7To8SurvivesOrphanEbook() = runTest {
        createVersion7Database {
            execSQL(
                "INSERT INTO books (id, isbn, title, authors, status, notes, dateAdded, searchText) VALUES " +
                    "(1, NULL, 'Emma', 'Jane Austen', 'TO_READ', '', 21, 'emma\njane austen')"
            )
            execSQL("INSERT INTO ebooks VALUES (99, 'EPUB', 'orphan.epub', 10, 5, NULL, NULL)")
        }
        val db = createAndroidDatabase(context, name)
        assertEquals(1, db.bookDao().getAll().size)
        val orphan = db.ebookDao().getAllAny().single()
        assertEquals("orphan.epub", orphan.fileName)
        assertTrue(Regex("^[0-9a-f-]{36}$").matches(orphan.uuid))
        db.close()
    }
}
