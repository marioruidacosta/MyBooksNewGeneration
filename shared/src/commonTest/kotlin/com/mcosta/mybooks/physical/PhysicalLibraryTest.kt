package com.mcosta.mybooks.physical

import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhysicalLibraryTest {
    private val stored =
        """{"v":1,"name":"By the window","room":"Living room","columns":[{"id":"c1","shelves":["s1","s2"]},{"id":"c2","shelves":["s3"]}]}"""
    private val window = Bookcase(
        "x", "By the window", "Living room",
        listOf(BookcaseColumn("c1", listOf("s1", "s2")), BookcaseColumn("c2", listOf("s3"))),
    )

    private fun ids(): () -> String {
        var n = 0
        return { "id${++n}" }
    }

    private fun book(id: Long, title: String, shelf: String? = null, position: Double? = null, status: ReadingStatus = ReadingStatus.READ) =
        Book(id = id, title = title, shelfId = shelf, shelfPosition = position, status = status)

    @Test
    fun theStoredFormatIsExact() {
        assertEquals(stored, window.toStored())
        assertEquals(window, parseBookcase("bookcase.x", stored))
    }

    @Test
    fun labels() {
        assertEquals("A2", shelfLabel(0, 1))
        assertEquals("C1", shelfLabel(2, 0))
        val place = assertNotNull(listOf(window).findShelf("s2"))
        assertEquals("A2", place.label)
        assertEquals("Living room › By the window › Shelf A2", place.location)
        assertNull(listOf(window).findShelf("nope"))
        assertNull(listOf(window).findShelf(null))
    }

    @Test
    fun newBookcaseClampsItsSize() {
        val case = newBookcase("  n ", " r ", 11, 16, ids())
        assertEquals("n", case.name)
        assertEquals("r", case.room)
        assertEquals(10, case.columns.size)
        assertTrue(case.columns.all { it.shelves.size == 15 })
        assertEquals(150, case.shelfIds().toSet().size)
        assertEquals(60, newBookcase("x".repeat(61), "r", 1, 1, ids()).name.length)
        assertEquals(1, newBookcase("n", "r", 0, 0, ids()).shelfIds().size)
    }

    @Test
    fun eachColumnCanDiffer() {
        val more = window.withShelfAdded(1, ids())
        assertEquals(listOf("s1", "s2"), more.columns[0].shelves)
        assertEquals(listOf("s3", "id1"), more.columns[1].shelves)
        val full = newBookcase("n", "r", 1, MAX_SHELVES, ids())
        assertEquals(full, full.withShelfAdded(0, ids()))
        val single = newBookcase("n", "r", 2, 1, ids())
        assertEquals(single, single.withoutShelf(single.columns[0].shelves[0]))
        val one = newBookcase("n", "r", 1, 2, ids())
        assertEquals(one, one.withoutColumn(0))
        assertEquals(2, window.withColumnAdded(3, ids()).columns.size - 1)
        assertEquals(3, window.withColumnAdded(3, ids()).columns[2].shelves.size)
        val wide = newBookcase("n", "r", MAX_COLUMNS, 1, ids())
        assertEquals(wide, wide.withColumnAdded(1, ids()))
    }

    @Test
    fun removingAShelfKeepsOtherIds() {
        val after = window.withoutShelf("s1")
        assertEquals(listOf("s2"), after.columns[0].shelves)
        assertEquals("A1", assertNotNull(listOf(after).findShelf("s2")).label)
    }

    @Test
    fun unreadableValuesAreSkipped() {
        assertNull(parseBookcase("bookcase.x", "nope"))
        assertNull(parseBookcase("bookcase.x", ""))
        assertNull(parseBookcase("bookcase.x", """{"v":1,"name":"n","room":"r","columns":[]}"""))
        assertNull(parseBookcase("other.x", stored))
    }

    @Test
    fun aNewerFormatIsReadOnly() {
        val case = assertNotNull(
            parseBookcase(
                "bookcase.x",
                """{"v":2,"name":"n","room":"r","depth":3,"columns":[{"id":"c","shelves":["s"],"width":2}]}""",
            ),
        )
        assertTrue(case.readOnly)
        assertEquals(listOf("s"), case.shelfIds())
    }

    @Test
    fun blankRoomReadsAsNoRoom() {
        val case = assertNotNull(parseBookcase("bookcase.x", """{"v":1,"name":"n","room":" ","columns":[{"id":"c","shelves":["s"]}]}"""))
        assertEquals("No room", case.room)
    }

    @Test
    fun roomsAndBookcasesSortByName() {
        fun case(name: String, room: String) = Bookcase(name, name, room, listOf(BookcaseColumn("c$name", listOf("s$name"))))
        val rooms = listOf(case("zed", "office"), case("Alpha", "Office"), case("mid", "Attic"), case("beta", "office")).byRoom()
        assertEquals(listOf("Attic", "office", "Office").map { it.lowercase() }.distinct(), rooms.map { it.first.lowercase() })
        assertEquals(listOf("Alpha", "beta", "zed"), rooms[1].second.map { it.name })
    }

    @Test
    fun shelfOrder() {
        val books = listOf(
            book(1, "Zola", "s1"),
            book(2, "Austen", "s1"),
            book(3, "Second", "s1", 2.0),
            book(4, "First", "s1", 1.0),
            book(5, "To buy", "s1", 0.5, ReadingStatus.WISHLIST),
            book(6, "Elsewhere", "s2", 1.0),
        )
        assertEquals(listOf(4L, 3L, 2L, 1L), books.onShelf("s1").map { it.id })
    }

    @Test
    fun withoutPlaceRules() {
        val books = listOf(
            book(1, "No shelf"),
            book(2, "Orphan", "gone", 1.0),
            book(3, "To buy", null, null, ReadingStatus.WISHLIST),
            book(4, "Ebook"),
            book(5, "Placed", "s1", 1.0),
        )
        assertEquals(listOf(1L, 2L), books.withoutPlace(listOf(window), setOf(4L)).map { it.id })
    }

    @Test
    fun endPosition() {
        assertEquals(1.0, endPosition(emptyList()))
        assertEquals(4.5, endPosition(listOf(book(1, "a", "s", 3.5), book(2, "b", "s", 1.0), book(3, "c", "s"))))
        assertEquals(1.0, endPosition(listOf(book(1, "a", "s"))))
    }

    @Test
    fun positionBetween() {
        assertEquals(1.0, positionBetween(null, null))
        assertEquals(1.0, positionBetween(null, 2.0))
        assertEquals(3.0, positionBetween(2.0, null))
        assertEquals(1.5, positionBetween(1.0, 2.0))
        assertNull(positionBetween(1.0, 1.0000001))
    }

    @Test
    fun renumberedKeepsTheVisibleOrder() {
        val shelf = listOf(book(1, "Zola", "s"), book(2, "Austen", "s"), book(3, "First", "s", 0.25)).onShelf("s")
        assertEquals(mapOf(3L to 1.0, 2L to 2.0, 1L to 3.0), renumbered(shelf))
    }

    private fun by(id: Long, title: String, authors: String, shelf: String? = null, position: Double? = null) =
        Book(id = id, title = title, authors = authors, shelfId = shelf, shelfPosition = position, status = ReadingStatus.READ)

    @Test
    fun findMatchesTitleAndAuthorTogether() {
        val books = listOf(
            by(1, "Ensaio sobre a Cegueira", "José Saramago"),
            by(2, "Memorial do Convento", "José Saramago"),
            by(3, "A Cegueira", "Outro"),
        )
        assertEquals(listOf(1L), books.findInHome(listOf(window), emptySet(), "saramago  CEGUEIRA").map { it.book.id })
        assertEquals(listOf(1L, 2L), books.findInHome(listOf(window), emptySet(), "jose").map { it.book.id })
        assertTrue(books.findInHome(listOf(window), emptySet(), "   ").isEmpty())
    }

    @Test
    fun findSaysWhereTheBookIs() {
        val books = listOf(by(1, "Alpha", "A", "s2", 1.0), by(2, "Target", "B", "s2", 2.0), by(3, "No order", "C", "s2"))
        val found = books.findInHome(listOf(window), emptySet(), "target").single()
        assertEquals("Living room › By the window › Shelf A2", found.place?.location)
        assertEquals(2, found.fromLeft)
        assertNull(books.findInHome(listOf(window), emptySet(), "no order").single().fromLeft)
    }

    @Test
    fun findLeavesOutWhatHasNoPlaceToBe() {
        val books = listOf(
            by(1, "Paper", "Someone"),
            by(2, "Ebook", "Someone"),
            by(3, "Placed ebook", "Someone", "s1", 1.0),
            by(4, "Gone", "Someone", "removed", 1.0),
            by(5, "To buy", "Someone", "s1", 2.0).copy(status = ReadingStatus.WISHLIST),
        )
        val found = books.findInHome(listOf(window), setOf(2L, 3L), "someone")
        assertEquals(listOf(4L, 1L, 3L), found.map { it.book.id })
        assertEquals(listOf(null, null, "s1"), found.map { it.place?.shelfId })
    }
}
