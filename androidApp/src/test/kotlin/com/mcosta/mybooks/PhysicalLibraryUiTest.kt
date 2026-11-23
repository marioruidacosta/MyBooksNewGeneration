package com.mcosta.mybooks

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.data.SaveResult
import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.db.createAndroidDatabase
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.physical.Bookcase
import com.mcosta.mybooks.physical.BookcaseColumn
import com.mcosta.mybooks.physical.BookcaseStore
import com.mcosta.mybooks.ui.LocalCoverStore
import com.mcosta.mybooks.ui.physical.BookcaseDrawing
import com.mcosta.mybooks.ui.physical.BookcaseEditScreen
import com.mcosta.mybooks.ui.physical.BookcaseScreen
import com.mcosta.mybooks.ui.physical.PickBooksContent
import com.mcosta.mybooks.ui.physical.ShelfScreen
import com.mcosta.mybooks.ui.physical.WhereItIsCard
import com.mcosta.mybooks.ui.physical.WithoutPlaceScreen
import com.mcosta.mybooks.ui.physical.ordinal
import com.mcosta.mybooks.ui.edit.EditBookViewModel
import com.mcosta.mybooks.ui.physical.PhysicalLibraryScreen
import com.mcosta.mybooks.ui.physical.PhysicalLibraryViewModel
import com.mcosta.mybooks.ui.settings.MoreScreen
import com.mcosta.mybooks.ui.theme.MyBooksTheme
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhysicalLibraryUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "physical-${System.nanoTime()}.db"
    private val db = createAndroidDatabase(context, dbName)
    private val values = mutableMapOf<String, String>()
    private val prefs = object : Preferences {
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String) {
            values[key] = value
        }
    }
    private val store = BookcaseStore(prefs, { values.keys.toList() }, {})
    private val repo = BookRepository(
        db.bookDao(),
        object : BookLookupService {
            override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
        },
        bookcases = store,
        ebooks = db.ebookDao(),
    )
    private val window = Bookcase(
        "w", "By the window", "Living room",
        listOf(BookcaseColumn("c1", listOf("s1", "s2")), BookcaseColumn("c2", listOf("s3"))),
    )

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    private fun until(condition: () -> Boolean) {
        compose.waitUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            condition()
        }
        compose.waitForIdle()
    }

    private fun addBook(title: String, shelf: String? = null, position: Double? = null, status: ReadingStatus = ReadingStatus.READ): Long =
        runBlocking { (repo.save(Book(title = title, authors = "Someone", status = status, shelfId = shelf, shelfPosition = position)) as SaveResult.Saved).id }

    private fun saved(case: Bookcase) = runBlocking { store.save(case) }

    private fun vmWith(books: Int): PhysicalLibraryViewModel {
        val vm = PhysicalLibraryViewModel(repo, store)
        until { vm.state.value.books.size == books }
        return vm
    }

    private fun home(vm: PhysicalLibraryViewModel) = compose.setContent {
        MyBooksTheme { PhysicalLibraryScreen(vm, onBack = {}, onOpenBookcase = {}, onAdd = {}, onOpenUnplaced = {}) }
    }

    private fun editor(vm: PhysicalLibraryViewModel, id: String?, onDone: () -> Unit = {}) = compose.setContent {
        MyBooksTheme { BookcaseEditScreen(vm, id, onDone) }
    }

    private fun undoShowing() = compose.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty()

    private fun shelfOf(id: Long) = runBlocking { repo.getBook(id)!!.shelfId }

    @Test
    fun undoBelongsToTheLatestAction() {
        saved(window)
        val a = addBook("A", "s1", 1.0)
        val b = addBook("B", "s1", 2.0)
        val vm = vmWith(2)
        home(vm)
        vm.takeOff(a)
        until { shelfOf(a) == null && undoShowing() }
        vm.takeOff(b)
        until { shelfOf(b) == null && undoShowing() }
        compose.onNodeWithText("Undo").performClick()
        until { shelfOf(a) != null || shelfOf(b) != null }
        assertEquals("s1", shelfOf(b))
        assertEquals(null, shelfOf(a))
    }

    @Test
    fun anOldUndoDoesNotComeBack() {
        saved(window)
        val a = addBook("A", "s1", 1.0)
        val vm = vmWith(1)
        var shown by mutableStateOf(true)
        compose.setContent {
            MyBooksTheme {
                if (shown) PhysicalLibraryScreen(vm, onBack = {}, onOpenBookcase = {}, onAdd = {}, onOpenUnplaced = {})
            }
        }
        vm.takeOff(a)
        until { shelfOf(a) == null && undoShowing() }
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        compose.runOnIdle { shown = true }
        compose.waitForIdle()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        compose.waitForIdle()
        compose.onNodeWithText("Undo").assertDoesNotExist()
    }

    @Test
    fun moreOpensPhysicalLibrary() {
        var opened = false
        compose.setContent {
            MyBooksTheme { MoreScreen(onOpenPhysical = { opened = true }, onOpenNews = {}, onOpenSettings = {}) }
        }
        val physical = compose.onNodeWithText("Physical library").getUnclippedBoundsInRoot().top
        val news = compose.onNodeWithText("Book news").getUnclippedBoundsInRoot().top
        assertTrue(physical < news)
        compose.onNodeWithText("Physical library").performClick()
        assertTrue(opened)
    }

    @Test
    fun emptyHomeInvitesABookcase() {
        home(vmWith(0))
        compose.onNodeWithText("Add a bookcase").assertExists()
    }

    @Test
    fun roomsListTheirBookcases() {
        saved(window)
        addBook("On a shelf", "s1", 1.0)
        addBook("On another", "s3", 1.0)
        addBook("Waiting")
        home(vmWith(3))
        compose.onNodeWithText("Living room").assertExists()
        compose.onNodeWithText("By the window").assertExists()
        compose.onNodeWithText("3 shelves · 2 books").assertExists()
        compose.onNodeWithText("1 book without a place").assertExists()
    }

    @Test
    fun aRoomCanBeRenamed() {
        saved(window)
        val vm = vmWith(0)
        home(vm)
        compose.onNodeWithContentDescription("Rename room Living room").performClick()
        compose.onNodeWithText("Room name").performTextInput("Lounge ")
        compose.onNodeWithText("Rename").performClick()
        until { store.bookcases.value.single().room.startsWith("Lounge") }
    }

    @Test
    fun editorDrawsWhatItWillSave() {
        val vm = vmWith(0)
        var done = false
        editor(vm, null) { done = true }
        compose.onNodeWithText("Name").performTextInput("Tall bookcase")
        compose.onNodeWithText("Room").performTextInput("Office")
        compose.onNodeWithContentDescription("Shelf B4, 0 books").performScrollTo().assertExists()
        compose.onNodeWithText("Save bookcase").performScrollTo().performClick()
        until { done && store.bookcases.value.size == 1 }
        val case = store.bookcases.value.single()
        assertEquals("Tall bookcase", case.name)
        assertEquals("Office", case.room)
        assertEquals(listOf(4, 4), case.columns.map { it.shelves.size })
    }

    @Test
    fun theSteppersChangeTheDrawing() {
        editor(vmWith(0), null)
        compose.onNodeWithContentDescription("More columns").performClick()
        compose.onNodeWithContentDescription("Fewer shelves").performClick()
        compose.onNodeWithContentDescription("Shelf C3, 0 books").performScrollTo().assertExists()
        compose.onAllNodesWithText("Shelf C4, 0 books").assertCountEquals0()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals0() {
        assertEquals(0, fetchSemanticsNodes().size)
    }

    @Test
    fun saveNeedsANameAndARoom() {
        editor(vmWith(0), null)
        compose.onNodeWithText("Save bookcase").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Name").performScrollTo().performTextInput("Tall")
        compose.onNodeWithText("Save bookcase").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Room").performScrollTo().performTextInput("Office")
        compose.onNodeWithText("Save bookcase").performScrollTo().assertIsEnabled()
    }

    @Test
    fun editingChangesOneColumn() {
        saved(newCase())
        val vm = vmWith(0)
        var done = false
        editor(vm, "n") { done = true }
        compose.onNodeWithContentDescription("Add a shelf to column B").performScrollTo().performClick()
        compose.onNodeWithText("Save bookcase").performScrollTo().performClick()
        until { done && store.bookcases.value.single().columns[1].shelves.size == 5 }
        assertEquals(listOf(4, 5), store.bookcases.value.single().columns.map { it.shelves.size })
    }

    private fun newCase() = Bookcase(
        "n", "Tall", "Office",
        listOf(BookcaseColumn("a", listOf("a1", "a2", "a3", "a4")), BookcaseColumn("b", listOf("b1", "b2", "b3", "b4"))),
    )

    @Test
    fun removingAShelfWithBooksAsksFirst() {
        saved(newCase())
        addBook("One", "a4", 1.0)
        addBook("Two", "a4", 2.0)
        val vm = vmWith(2)
        var done = false
        editor(vm, "n") { done = true }
        compose.onNodeWithContentDescription("Remove a shelf from column A").performScrollTo().performClick()
        compose.onNodeWithText("2 books will go to Without a place.").assertExists()
        compose.onNodeWithText("Remove shelf").performClick()
        compose.onNodeWithText("Save bookcase").performScrollTo().performClick()
        until { done && store.bookcases.value.single().columns[0].shelves.size == 3 && vm.state.value.withoutPlace.size == 2 }
    }

    @Test
    fun deletingABookcaseAsksFirstAndKeepsTheBooks() {
        saved(window)
        addBook("One", "s1", 1.0)
        val vm = vmWith(1)
        var done = false
        editor(vm, "w") { done = true }
        compose.onNodeWithText("Delete bookcase").performScrollTo().performClick()
        compose.onNodeWithText("1 book will go to Without a place.").assertExists()
        compose.onNodeWithText("Delete").performClick()
        until { done && store.bookcases.value.isEmpty() && vm.state.value.withoutPlace.size == 1 }
    }

    @Test
    fun aReadOnlyBookcaseCantBeEdited() {
        store.pulled("bookcase.r", """{"v":2,"name":"Newer","room":"Office","columns":[{"id":"c","shelves":["s"]}]}""")
        editor(vmWith(0), "r")
        compose.onNodeWithText("Made by a newer version of MyBooks. Update the app to change it.").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Save bookcase").fetchSemanticsNodes().size)
    }

    @Test
    fun aFullShelfShowsHowManyMore() {
        val books = List(80) { Book(id = it + 1L, title = "Book $it", shelfId = "s1", shelfPosition = it.toDouble()) }
        compose.setContent {
            MyBooksTheme {
                CompositionLocalProvider(LocalCoverStore provides CoverStore(FileSystem.SYSTEM, context.filesDir.toOkioPath() / "covers")) {
                    BookcaseDrawing(window, { id -> if (id == "s1") books else emptyList() }, shelfHeight = 56.dp, labels = true)
                }
            }
        }
        compose.onNodeWithContentDescription("not shown", substring = true).assertExists()
    }

    // ---- inside a bookcase ----

    private fun shelf(vm: PhysicalLibraryViewModel, id: String = "s1", onOpenBook: (Long) -> Unit = {}) = compose.setContent {
        MyBooksTheme { ShelfScreen(vm, id, onBack = {}, onOpenBook = onOpenBook, onScan = null) }
    }

    @Test
    fun tappingAShelfOpensIt() {
        saved(window)
        val vm = vmWith(0)
        var opened: String? = null
        compose.setContent { MyBooksTheme { BookcaseScreen(vm, "w", onBack = {}, onEdit = {}, onOpenShelf = { opened = it }) } }
        compose.onNodeWithText("By the window").assertExists()
        compose.onNodeWithContentDescription("Shelf A2, 0 books").performClick()
        assertEquals("s2", opened)
    }

    @Test
    fun aRemovedBookcaseLeavesItsScreen() {
        val vm = vmWith(0)
        var back = false
        compose.setContent { MyBooksTheme { BookcaseScreen(vm, "gone", onBack = { back = true }, onEdit = {}, onOpenShelf = {}) } }
        until { back }
    }

    @Test
    fun aShelfListsBooksInOrder() {
        saved(window)
        addBook("Second", "s1", 2.0)
        val first = addBook("First", "s1", 1.0)
        runBlocking { repo.lend(first, "Ana", 1) }
        val vm = vmWith(2)
        shelf(vm)
        compose.onNodeWithText("Shelf A1").assertExists()
        val top = compose.onNodeWithText("First").getUnclippedBoundsInRoot().top
        val next = compose.onNodeWithText("Second").getUnclippedBoundsInRoot().top
        assertTrue(top < next)
        compose.onNodeWithText("Lent to Ana", substring = true).assertExists()
    }

    @Test
    fun moveDownChangesTheOrder() {
        saved(window)
        val a = addBook("Alpha", "s1", 1.0)
        val b = addBook("Beta", "s1", 2.0)
        val vm = vmWith(2)
        shelf(vm)
        val action = compose.onNodeWithText("Alpha").fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Move down" }
        compose.runOnIdle { action.action() }
        until { vm.booksOn("s1").map { it.id } == listOf(b, a) }
    }

    @Test
    fun takingABookOffSendsItToWithoutAPlace() {
        saved(window)
        val a = addBook("Alpha", "s1", 1.0)
        val vm = vmWith(1)
        shelf(vm)
        compose.onNodeWithContentDescription("Take Alpha off this shelf").performClick()
        until { vm.state.value.withoutPlace.map { it.id } == listOf(a) }
    }

    @Test
    fun pickSearchesAndPlaces() {
        saved(window)
        val a = addBook("Stoner")
        val b = addBook("Stone Junction")
        addBook("Emma")
        val vm = vmWith(3)
        var picked: List<Long>? = null
        compose.setContent {
            MyBooksTheme { PickBooksContent(vm.state.value.books, vm.state.value.bookcases, vm.state.value.ebookIds, "s1") { picked = it } }
        }
        compose.onNodeWithText("Search your books").performTextInput("stone")
        assertEquals(0, compose.onAllNodesWithText("Emma").fetchSemanticsNodes().size)
        compose.onNodeWithText("Stoner").performClick()
        compose.onNodeWithText("Stone Junction").performClick()
        compose.onNodeWithText("Put 2 on this shelf").performClick()
        assertEquals(setOf(a, b), picked?.toSet())
    }

    @Test
    fun placingFromTheShelfOffersUndo() {
        saved(window)
        val a = addBook("Stoner")
        val vm = vmWith(1)
        shelf(vm)
        compose.runOnIdle { vm.place(listOf(a), "s1") }
        until { vm.booksOn("s1").size == 1 && vm.state.value.undo != null }
        compose.onNodeWithText("Undo").assertExists()
        compose.onNodeWithText("Undo").performClick()
        until { vm.booksOn("s1").isEmpty() }
    }

    @Test
    fun pickShowsWhereABookIs() {
        saved(window)
        addBook("Stoner", "s2", 1.0)
        val vm = vmWith(1)
        compose.setContent {
            MyBooksTheme { PickBooksContent(vm.state.value.books, vm.state.value.bookcases, vm.state.value.ebookIds, "s1") {} }
        }
        compose.onNodeWithText("Living room › By the window › Shelf A2", substring = true).assertExists()
    }

    @Test
    fun pickHidesToBuyAndEbooks() {
        saved(window)
        addBook("Paper")
        addBook("To buy", status = ReadingStatus.WISHLIST)
        val ebook = addBook("Ebook")
        val already = addBook("Already here", "s1", 1.0)
        val vm = vmWith(4)
        compose.setContent {
            MyBooksTheme { PickBooksContent(vm.state.value.books, vm.state.value.bookcases, setOf(ebook), "s1") {} }
        }
        compose.onNodeWithText("Paper").assertExists()
        for (hidden in listOf("To buy", "Ebook", "Already here")) {
            assertEquals(hidden, 0, compose.onAllNodesWithText(hidden).fetchSemanticsNodes().size)
        }
        assertTrue(already > 0)
    }

    @Test
    fun withoutPlaceCanPlaceSeveral() {
        saved(window)
        val a = addBook("Alpha")
        val b = addBook("Beta")
        val vm = vmWith(2)
        compose.setContent { MyBooksTheme { WithoutPlaceScreen(vm, onBack = {}, onOpenBook = {}) } }
        compose.onNodeWithContentDescription("Select Alpha").performClick()
        compose.onNodeWithContentDescription("Select Beta").performClick()
        compose.onNodeWithText("Put 2 on a shelf").performClick()
        compose.onNodeWithText("Living room › By the window › Shelf A2").performClick()
        until { vm.booksOn("s2").map { it.id }.toSet() == setOf(a, b) }
    }

    // ---- on the book's page ----

    private fun card(
        vm: PhysicalLibraryViewModel,
        id: Long,
        onChoose: (String) -> Unit = {},
        onTakeOff: () -> Unit = {},
        onAddBookcase: () -> Unit = {},
    ) {
        val book = vm.state.value.books.first { it.id == id }
        compose.setContent {
            MyBooksTheme {
                WhereItIsCard(book, vm.state.value.bookcases, vm::booksOn, onChoose, onTakeOff, onOpenShelf = {}, onAddBookcase = onAddBookcase)
            }
        }
    }

    @Test
    fun showsTheFullLocation() {
        saved(window)
        addBook("One", "s2", 1.0)
        addBook("Two", "s2", 2.0)
        addBook("Three", "s2", 3.0)
        val id = addBook("Four", "s2", 4.0)
        val vm = vmWith(4)
        card(vm, id)
        compose.onNodeWithText("Living room › By the window › Shelf A2").assertExists()
        compose.onNodeWithText("4th from the left").assertExists()
    }

    @Test
    fun ordinals() {
        assertEquals(
            listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "101st", "111th"),
            listOf(1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 101, 111).map(::ordinal),
        )
    }

    @Test
    fun aBookWithNoOrderDoesNotClaimOne() {
        saved(window)
        val id = addBook("One", "s2", null)
        val vm = vmWith(1)
        card(vm, id)
        compose.onNodeWithText("Living room › By the window › Shelf A2").assertExists()
        assertEquals(0, compose.onAllNodesWithText("from the left", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun withoutAPlaceOffersPuttingItOnAShelf() {
        saved(window)
        val id = addBook("Free")
        val vm = vmWith(1)
        var chosen: String? = null
        card(vm, id, onChoose = { chosen = it })
        compose.onNodeWithText("Put on a shelf").performClick()
        compose.onNodeWithText("Living room › By the window › Shelf B1").performClick()
        assertEquals("s3", chosen)
    }

    @Test
    fun moveChangesTheShelf() {
        saved(window)
        val id = addBook("Book", "s1", 1.0)
        val vm = vmWith(1)
        var chosen: String? = null
        card(vm, id, onChoose = { chosen = it })
        compose.onNodeWithText("Move").performClick()
        assertEquals(0, compose.onAllNodesWithText("Living room › By the window › Shelf A1", useUnmergedTree = true).fetchSemanticsNodes().size - 1)
        compose.onNodeWithText("Living room › By the window › Shelf A2").performClick()
        assertEquals("s2", chosen)
    }

    @Test
    fun hiddenForToBuy() {
        saved(window)
        val id = addBook("Wish", status = ReadingStatus.WISHLIST)
        val vm = vmWith(1)
        card(vm, id)
        assertEquals(0, compose.onAllNodesWithText("Where it is").fetchSemanticsNodes().size)
    }

    @Test
    fun anEbookCanStillBePlaced() {
        saved(window)
        val id = addBook("Ebook")
        runBlocking {
            db.ebookDao().upsert(com.mcosta.mybooks.data.db.EbookEntity(bookId = id, format = com.mcosta.mybooks.domain.EbookFormat.EPUB, fileName = "a.epub", size = 1, dateAdded = 1))
        }
        val vm = vmWith(1)
        until { vm.state.value.ebookIds == setOf(id) }
        card(vm, id)
        compose.onNodeWithText("Put on a shelf").assertExists()
    }

    @Test
    fun withoutBookcasesItLeadsToCreatingOne() {
        val id = addBook("Book")
        val vm = vmWith(1)
        var adding = false
        card(vm, id, onAddBookcase = { adding = true })
        compose.onNodeWithText("Add a bookcase").performClick()
        assertTrue(adding)
    }

    @Test
    fun lentShowsBoth() {
        saved(window)
        val id = addBook("Lent", "s1", 1.0)
        runBlocking { repo.lend(id, "Ana", 1) }
        val vm = PhysicalLibraryViewModel(repo, store)
        until { vm.state.value.books.singleOrNull()?.lentTo == "Ana" }
        card(vm, id)
        compose.onNodeWithText("Lent to Ana", substring = true).assertExists()
        compose.onNodeWithText("Living room › By the window › Shelf A1").assertExists()
    }

    @Test
    fun aBookEditedKeepsItsLocation() {
        saved(window)
        val id = addBook("Before", "s2", 3.0)
        val edit = EditBookViewModel(repo, id, null)
        until { edit.state.value.form.title == "Before" }
        edit.update { it.copy(title = "After") }
        edit.save()
        until { edit.state.value.savedId == id }
        val book = runBlocking { repo.getBook(id)!! }
        assertEquals("After", book.title)
        assertEquals("s2", book.shelfId)
        assertEquals(3.0, book.shelfPosition)
    }
}
