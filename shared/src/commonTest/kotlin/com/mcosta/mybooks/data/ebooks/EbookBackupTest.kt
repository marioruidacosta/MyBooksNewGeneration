package com.mcosta.mybooks.data.ebooks

import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.FakeBookmarkDao
import com.mcosta.mybooks.data.FakeEbookDao
import com.mcosta.mybooks.data.FakeQuoteDao
import com.mcosta.mybooks.data.SaveResult
import com.mcosta.mybooks.data.db.BookmarkEntity
import com.mcosta.mybooks.data.db.EbookEntity
import com.mcosta.mybooks.data.db.QuoteEntity
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.EbookFormat
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Backups keep where you were in an ebook, its bookmarks and its highlights, never the file itself. */
class EbookBackupTest {
    private val noLookup = object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
    }

    private class Library {
        val books = FakeBookDao()
        val quotes = FakeQuoteDao()
        val ebooks = FakeEbookDao()
        val bookmarks = FakeBookmarkDao()
    }

    private fun repo(l: Library) = BookRepository(l.books, noLookup, quotes = l.quotes, ebooks = l.ebooks, bookmarks = l.bookmarks)

    @Test
    fun backupKeepsPositionsBookmarksAndHighlightsButNoFile() = runTest {
        val mine = Library()
        val repo = repo(mine)
        val id = (repo.save(Book(title = "Os Maias", isbn = "9789722039994")) as SaveResult.Saved).id
        mine.ebooks.upsert(EbookEntity(id, EbookFormat.EPUB, "os-maias.epub", 512_000, dateAdded = 7, position = "{\"p\":3}", lastOpened = 9))
        mine.bookmarks.insert(BookmarkEntity(bookId = id, position = "{\"p\":1}", label = "Capítulo I", dateAdded = 1))
        mine.bookmarks.insert(BookmarkEntity(bookId = id, position = "{\"p\":2}", label = "Capítulo II", dateAdded = 2))
        mine.quotes.insert(QuoteEntity(bookId = id, text = "O Ega…", page = null, dateAdded = 3, position = "{\"p\":2}"))
        val json = repo.exportJson()
        assertTrue("os-maias.epub" in json)
        assertFalse("UEsDB" in json)          // no zip (EPUB) bytes in base64

        val other = Library()
        val restore = repo(other)
        restore.importJson(json)
        restore.importJson(json)
        val newId = restore.findByIsbn("9789722039994")!!.id
        val ebook = other.ebooks.get(newId)!!
        assertEquals(EbookEntity(newId, EbookFormat.EPUB, "os-maias.epub", 512_000, 7, "{\"p\":3}", 9, uuid = ebook.uuid), ebook)
        assertEquals(listOf("Capítulo I", "Capítulo II"), other.bookmarks.forBook(newId).map { it.label })
        assertEquals("{\"p\":2}", other.quotes.forBook(newId).single().position)
    }

    /** A file already on this phone keeps its own place: restoring an older backup doesn't move it. */
    @Test
    fun anEbookAlreadyHereIsNotReplaced() = runTest {
        val mine = Library()
        val repo = repo(mine)
        val id = (repo.save(Book(title = "Os Maias", isbn = "9789722039994")) as SaveResult.Saved).id
        mine.ebooks.upsert(EbookEntity(id, EbookFormat.EPUB, "os-maias.epub", 512_000, dateAdded = 7, position = "{\"p\":3}"))
        val json = repo.exportJson()
        mine.ebooks.setPosition(id, "{\"p\":8}", lastOpened = 20)
        repo.importJson(json)
        assertEquals("{\"p\":8}", mine.ebooks.get(id)!!.position)
    }
}
