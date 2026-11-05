package com.mcosta.mybooks.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
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
}
