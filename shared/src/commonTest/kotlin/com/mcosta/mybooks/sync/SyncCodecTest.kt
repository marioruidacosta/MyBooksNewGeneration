package com.mcosta.mybooks.sync

import com.mcosta.mybooks.data.db.BookEntity
import com.mcosta.mybooks.data.db.BookmarkEntity
import com.mcosta.mybooks.data.db.ChallengeEntity
import com.mcosta.mybooks.data.db.EbookEntity
import com.mcosta.mybooks.data.db.QuoteEntity
import com.mcosta.mybooks.domain.EbookFormat
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.domain.searchKey
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncCodecTest {
    private fun book(cover: String? = "https://x/y.jpg") = BookEntity(
        id = 42, isbn = "9781234567890", title = "Les Misérables", authors = "Victor Hugo", publisher = "Pub",
        publishedYear = 1862, pageCount = 1200, coverUrl = cover, description = "Long", status = ReadingStatus.READ,
        rating = 5, notes = "n", dateAdded = 1000, dateRead = 2000, searchText = searchKey("Les Misérables\nVictor Hugo"),
        dateStarted = 1500, currentPage = 7, progressPercent = 50, review = "great", tags = "a\nb", series = "S",
        seriesNumber = "2", lentTo = "Ann", lentOn = 3000, uuid = "book-uuid", fieldTimes = """{"deleted":10,"title":10}""",
        deleted = false,
    )

    @Test
    fun bookRoundTripsEveryField() {
        val b = book()
        val row = b.toRow(null)
        assertEquals("book-uuid", row.id)
        assertEquals(SyncTable.BOOKS, row.table)
        assertEquals(JsonPrimitive(1862), row.fields["published_year"])
        assertEquals(JsonPrimitive("a\nb"), row.fields["tags"])
        assertEquals(b, row.toBookEntity(b, null))
        assertEquals(b.copy(id = 0), row.toBookEntity(null, null))
    }

    @Test
    fun coverPhotoTravelsAsMarker() {
        val row = book("cover:abc.jpg").toRow(null)
        assertEquals(JsonPrimitive("photo"), row.fields["cover_url"])
        assertEquals("cover:book-uuid.jpg", row.toBookEntity(null, null).coverUrl)
        assertEquals(JsonPrimitive("https://x/y.jpg"), book().toRow(null).fields["cover_url"])
    }

    @Test
    fun quoteCarriesBookUuid() = runTest {
        val q = QuoteEntity(id = 3, bookId = 42, text = "t", page = 9, dateAdded = 5, position = "p", uuid = "q-uuid")
        val row = q.toRow { if (it == 42L) "book-uuid" else null }!!
        assertEquals(JsonPrimitive("book-uuid"), row.fields["book_id"])
        assertEquals(q, row.toQuoteEntity(q) { if (it == "book-uuid") 42L else null })
        assertNull(row.toQuoteEntity(null) { null })
    }

    @Test
    fun bookmarkEbookChallengeAndPrefRoundTrip() = runTest {
        val bm = BookmarkEntity(id = 4, bookId = 42, position = "pos", label = "l", dateAdded = 8, uuid = "bm-uuid")
        assertEquals(bm, bm.toRow { "book-uuid" }!!.toBookmarkEntity(bm) { 42L })

        val e = EbookEntity(bookId = 42, format = EbookFormat.PDF, fileName = "f.pdf", size = 123456789012, dateAdded = 1,
            position = "p", lastOpened = 2, uuid = "book-uuid")
        val er = e.toRow { "book-uuid" }!!
        assertEquals("book-uuid", er.id)
        assertEquals(JsonPrimitive(123456789012), er.fields["size"])
        assertEquals(e, er.toEbookEntity(e) { 42L })

        val c = ChallengeEntity(2026, 30, """{"goal":5}""", false)
        assertEquals("2026", c.toRow().id)
        assertEquals(c, c.toRow().toChallengeEntity())

        val p = prefRow("theme", "dark", 77)
        assertEquals("dark", p.prefValue())
        assertEquals(mapOf("value" to 77L, "deleted" to 77L), p.times)
        assertNull(prefRow("theme", null, 78).prefValue())
    }
}
