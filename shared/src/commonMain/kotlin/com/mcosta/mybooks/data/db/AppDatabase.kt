package com.mcosta.mybooks.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import com.mcosta.mybooks.domain.searchKey
import com.mcosta.mybooks.domain.splitSeries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

@Database(entities = [BookEntity::class, ShareStateEntity::class, ChallengeEntity::class, QuoteEntity::class, EbookEntity::class, BookmarkEntity::class, SyncQueueEntity::class, SyncStateEntity::class], version = 8, exportSchema = true)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun shareStateDao(): ShareStateDao
    abstract fun challengeDao(): ChallengeDao
    abstract fun quoteDao(): QuoteDao
    abstract fun ebookDao(): EbookDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun syncStateDao(): SyncStateDao
}

/** Implemented by Room's code generator for each platform. */
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

/** Finishes a platform's database builder: migrations, SQLite driver and IO dispatcher. */
fun createDatabase(builder: RoomDatabase.Builder<AppDatabase>, driver: SQLiteDriver): AppDatabase =
    builder
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
        .setDriver(driver)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

/** v2: reading start date and progress; old Open Library cover-by-ISBN links ask for a 404 instead of a blank GIF. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE books ADD COLUMN dateStarted INTEGER")
        connection.execSQL("ALTER TABLE books ADD COLUMN currentPage INTEGER")
        connection.execSQL("ALTER TABLE books ADD COLUMN progressPercent INTEGER")
        connection.execSQL(
            "UPDATE books SET coverUrl = coverUrl || '?default=false' " +
                "WHERE coverUrl LIKE 'https://covers.openlibrary.org/b/isbn/%' AND coverUrl NOT LIKE '%?%'"
        )
    }
}

/** v3: public review on books, and the community sharing state of each book. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE books ADD COLUMN review TEXT NOT NULL DEFAULT ''")
        // Exactly the table Room generates (schemas/…/3.json).
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `share_state` (`bookId` INTEGER NOT NULL, `shareId` TEXT NOT NULL, " +
                "`owner` TEXT NOT NULL, `hasBefore` INTEGER NOT NULL, `beforeStatus` TEXT, `beforeRating` INTEGER, " +
                "`beforeProgress` INTEGER, `beforeReview` TEXT, `pending` INTEGER NOT NULL, `excluded` INTEGER NOT NULL, " +
                "`deleted` INTEGER NOT NULL, PRIMARY KEY(`bookId`))"
        )
    }
}

/** v4: yearly reading challenges. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        // Exactly the table Room generates (schemas/…/4.json).
        connection.execSQL("CREATE TABLE IF NOT EXISTS `challenges` (`year` INTEGER NOT NULL, `goal` INTEGER NOT NULL, PRIMARY KEY(`year`))")
    }
}

/**
 * v5: tags, series and loans. Titles imported from Goodreads with their series written in, as in
 * "The Hobbit (Middle-earth, #0.5)", get the series moved to its own field.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE books ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
        connection.execSQL("ALTER TABLE books ADD COLUMN series TEXT")
        connection.execSQL("ALTER TABLE books ADD COLUMN seriesNumber TEXT")
        connection.execSQL("ALTER TABLE books ADD COLUMN lentTo TEXT")
        connection.execSQL("ALTER TABLE books ADD COLUMN lentOn INTEGER")
        val split = mutableListOf<Triple<Long, String, com.mcosta.mybooks.domain.SeriesTitle>>()
        connection.prepare("SELECT id, title, authors FROM books WHERE title LIKE '%#%)%'").use { row ->
            while (row.step()) {
                splitSeries(row.getText(1))?.let { split += Triple(row.getLong(0), row.getText(2), it) }
            }
        }
        for ((id, authors, s) in split) {
            connection.prepare("UPDATE books SET title = ?, series = ?, seriesNumber = ?, searchText = ? WHERE id = ?").use { st ->
                st.bindText(1, s.title)
                st.bindText(2, s.series)
                if (s.number != null) st.bindText(3, s.number) else st.bindNull(3)
                st.bindText(4, searchKey("${s.title}\n$authors"))
                st.bindLong(5, id)
                st.step()
            }
        }
    }
}

/** v6: quotes from books. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(connection: SQLiteConnection) {
        // Exactly the table and index Room generates (schemas/…/6.json).
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `quotes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, " +
                "`text` TEXT NOT NULL, `page` INTEGER, `dateAdded` INTEGER NOT NULL)"
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_quotes_bookId` ON `quotes` (`bookId`)")
    }
}

/** v7: ebook files and bookmarks, and where a highlight is in its ebook. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(connection: SQLiteConnection) {
        // Exactly the tables and index Room generates (schemas/…/7.json).
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `ebooks` (`bookId` INTEGER NOT NULL, `format` TEXT NOT NULL, `fileName` TEXT NOT NULL, " +
                "`size` INTEGER NOT NULL, `dateAdded` INTEGER NOT NULL, `position` TEXT, `lastOpened` INTEGER, PRIMARY KEY(`bookId`))"
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, " +
                "`position` TEXT NOT NULL, `label` TEXT NOT NULL, `dateAdded` INTEGER NOT NULL)"
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)")
        connection.execSQL("ALTER TABLE quotes ADD COLUMN position TEXT")
    }
}

/** A random v4 uuid in SQL (lowercase, 8-4-4-4-12), evaluated once per row. */
private const val SQL_UUID =
    "lower(hex(randomblob(4)))||'-'||lower(hex(randomblob(2)))||'-4'||substr(lower(hex(randomblob(2))),2)||'-'||" +
        "substr('89ab',abs(random())%4+1,1)||substr(lower(hex(randomblob(2))),2)||'-'||lower(hex(randomblob(6)))"

/**
 * v8: cloud sync. Rows get a uuid (a shared book keeps its share id), the time each field last changed and a
 * deleted flag; the upload queue and the sync key/value store are new. An ebook's uuid is its book's.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(connection: SQLiteConnection) {
        for (table in listOf("books", "quotes", "ebooks", "bookmarks")) {
            connection.execSQL("ALTER TABLE $table ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
        }
        for (table in listOf("books", "quotes", "ebooks", "bookmarks", "challenges")) {
            connection.execSQL("ALTER TABLE $table ADD COLUMN fieldTimes TEXT NOT NULL DEFAULT '{}'")
            connection.execSQL("ALTER TABLE $table ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        }
        for (table in listOf("books", "quotes", "bookmarks")) {
            connection.execSQL("UPDATE $table SET uuid = $SQL_UUID")
        }
        connection.execSQL("UPDATE books SET uuid = (SELECT shareId FROM share_state WHERE share_state.bookId = books.id) " +
            "WHERE id IN (SELECT bookId FROM share_state)")
        connection.execSQL("UPDATE ebooks SET uuid = (SELECT uuid FROM books WHERE books.id = ebooks.bookId)")
        // Exactly the indexes and tables Room generates (schemas/…/8.json).
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_books_uuid` ON `books` (`uuid`)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_quotes_uuid` ON `quotes` (`uuid`)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bookmarks_uuid` ON `bookmarks` (`uuid`)")
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_queue` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`table` TEXT NOT NULL, `rowId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
        )
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_queue_table_rowId` ON `sync_queue` (`table`, `rowId`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `sync_state` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
    }
}
