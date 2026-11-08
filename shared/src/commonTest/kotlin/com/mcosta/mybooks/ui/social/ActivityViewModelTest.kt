package com.mcosta.mybooks.ui.social

import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.social.FakeSocial
import com.mcosta.mybooks.social.model.ActivityKind
import com.mcosta.mybooks.social.model.FeedScope
import com.mcosta.mybooks.social.model.ReportReason
import com.mcosta.mybooks.social.model.Visibility
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlinx.coroutines.flow.first
import kotlin.test.assertTrue

class ActivityViewModelTest : MainDispatcherTest() {
    private val fake = FakeSocial()
    private val ana = fake.addUser("ana")
    private val bruno = fake.addUser("bruno")
    private val carla = fake.addUser("carla").also { fake.setVisibility(it, Visibility.PUBLIC) }
    private val dao = FakeBookDao()
    private val repo = BookRepository(dao, object : BookLookupService {
        override suspend fun lookup(isbn: String) = LookupResult.NotFound
    })

    private fun vm() = ActivityViewModel(fake.services, repo)

    @Test
    fun signedOutShowsJoinState() = runTest {
        val vm = vm()
        assertFalse(vm.state.value.signedIn)
        assertTrue(vm.state.value.entries.isEmpty())
    }

    @Test
    fun friendsTabLoadsFirstPage() = runTest {
        fake.makeFriends(ana, bruno)
        fake.addEntry(bruno, ActivityKind.STARTED, title = "Dune")
        fake.addEntry(carla, ActivityKind.RATED, title = "Emma")
        fake.signInAs(ana)
        val vm = vm()
        assertTrue(vm.state.value.signedIn)
        assertEquals(FeedScope.FRIENDS, vm.state.value.scope)
        assertEquals(listOf("Dune"), vm.state.value.entries.map { it.book.title })
        assertTrue(vm.state.value.hasFriends)
    }

    @Test
    fun switchingScopeReloads() = runTest {
        fake.makeFriends(ana, bruno)
        fake.addEntry(bruno, ActivityKind.STARTED, title = "Dune")
        fake.addEntry(carla, ActivityKind.RATED, title = "Emma")
        fake.signInAs(ana)
        val vm = vm()
        vm.selectScope(FeedScope.EVERYONE)
        assertEquals(listOf("Emma"), vm.state.value.entries.map { it.book.title })
    }

    @Test
    fun loadMoreAppendsAndStopsAtEnd() = runTest {
        repeat(25) { fake.addEntry(carla, ActivityKind.PROGRESS, title = "Book $it") }
        fake.signInAs(ana)
        val vm = vm()
        vm.selectScope(FeedScope.EVERYONE)
        assertEquals(20, vm.state.value.entries.size)
        vm.loadMore()
        assertEquals(25, vm.state.value.entries.size)
        assertNull(vm.state.value.next)
        vm.loadMore()
        assertEquals(25, vm.state.value.entries.size)
    }

    @Test
    fun noFriendsFlagged() = runTest {
        fake.signInAs(ana)
        assertFalse(vm().state.value.hasFriends)
    }

    @Test
    fun likeIsOptimistic() = runTest {
        val id = fake.addEntry(carla, ActivityKind.RATED)
        fake.signInAs(ana)
        val vm = vm()
        vm.selectScope(FeedScope.EVERYONE)
        vm.toggleLike(id)
        val entry = vm.state.value.entries.single()
        assertTrue(entry.likedByMe)
        assertEquals(1, entry.likeCount)
        vm.toggleLike(id)
        assertFalse(vm.state.value.entries.single().likedByMe)
        assertEquals(0, vm.state.value.entries.single().likeCount)
    }

    @Test
    fun likeRollsBackOnFailure() = runTest {
        val id = fake.addEntry(carla, ActivityKind.RATED)
        fake.signInAs(ana)
        val vm = vm()
        vm.selectScope(FeedScope.EVERYONE)
        fake.offline = true
        vm.toggleLike(id)
        val entry = vm.state.value.entries.single()
        assertFalse(entry.likedByMe)
        assertEquals(0, entry.likeCount)
        assertEquals("You're offline", vm.state.value.message)
    }

    @Test
    fun likersShown() = runTest {
        val id = fake.addEntry(carla, ActivityKind.RATED)
        fake.signInAs(bruno)
        fake.activityService.like(id)
        fake.signInAs(ana)
        val vm = vm()
        vm.showLikers(id)
        assertEquals(listOf("bruno"), vm.state.value.likers?.map { it.username })
        vm.dismissLikers()
        assertNull(vm.state.value.likers)
    }

    @Test
    fun reportPassesActivityId() = runTest {
        val id = fake.addEntry(carla, ActivityKind.REVIEWED)
        fake.signInAs(ana)
        val vm = vm()
        vm.selectScope(FeedScope.EVERYONE)
        vm.report(vm.state.value.entries.single(), ReportReason.SPAM, "  spoilers  ")
        val report = fake.reports.single()
        assertEquals(id, report.activityId)
        assertEquals(carla, report.reported)
        assertEquals("spoilers", report.details)
        assertEquals("Thanks, we'll look into it", vm.state.value.message)
    }

    @Test
    fun openBookDetectsExistingByIsbn() = runTest {
        repo.save(Book(title = "Dune", isbn = "9780441013593"))
        fake.addEntry(carla, ActivityKind.FINISHED, title = "Dune (Ace)", isbn = "9780441013593")
        fake.addEntry(carla, ActivityKind.FINISHED, title = "Emma")
        fake.signInAs(ana)
        val vm = vm()
        vm.selectScope(FeedScope.EVERYONE)
        val (emma, dune) = vm.state.value.entries
        vm.openBook(dune.book)
        assertTrue(vm.state.value.openBookInLibrary)
        vm.openBook(emma.book)
        assertFalse(vm.state.value.openBookInLibrary)
        vm.closeBook()
        assertNull(vm.state.value.openBook)
    }

    @Test
    fun addToLibrarySavesToBuy() = runTest {
        fake.addEntry(carla, ActivityKind.FINISHED, title = "Emma", isbn = "9780141439587")
        fake.signInAs(ana)
        val vm = vm()
        vm.selectScope(FeedScope.EVERYONE)
        vm.openBook(vm.state.value.entries.single().book)
        vm.addToLibrary(ReadingStatus.WISHLIST)
        val saved = dao.getAll().single()
        assertEquals("Emma", saved.title)
        assertEquals("9780141439587", saved.isbn)
        assertEquals(ReadingStatus.WISHLIST, saved.status)
        assertEquals("Added to To buy", vm.state.value.message)
        assertTrue(vm.state.value.openBookInLibrary)
        vm.addToLibrary(ReadingStatus.TO_READ)
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun errorShowsRetryMessage() = runTest {
        fake.signInAs(ana)
        fake.offline = true
        val vm = vm()
        assertEquals("You're offline", vm.state.value.error)
        fake.offline = false
        vm.refresh()
        assertNull(vm.state.value.error)
    }

    @Test
    fun ownEntryCannotBeLiked() = runTest {
        val id = fake.addEntry(ana, ActivityKind.STARTED)
        fake.signInAs(ana)
        val vm = vm()
        vm.toggleLike(id)
        assertFalse(vm.state.value.entries.single().likedByMe)
        assertNull(vm.state.value.message)
    }

    @Test
    fun secondTapWhileLikeInFlightIgnored() = runTest {
        val id = fake.addEntry(carla, ActivityKind.RATED)
        fake.signInAs(ana)
        val vm = vm()
        vm.selectScope(FeedScope.EVERYONE)
        val gate = CompletableDeferred<Unit>()
        fake.onLike = { gate.await() }
        vm.toggleLike(id)
        vm.toggleLike(id)
        assertTrue(vm.state.value.entries.single().likedByMe)
        gate.complete(Unit)
        assertTrue(vm.state.value.entries.single().likedByMe)
        assertEquals(listOf("ana"), fake.activityService.likers(id).map { it.username })
        fake.onLike = {}
        vm.toggleLike(id)
        assertFalse(vm.state.value.entries.single().likedByMe)
    }

    private fun reading(title: String) = com.mcosta.mybooks.social.model.FriendReading(
        com.mcosta.mybooks.social.model.Person(bruno, "bruno", "Bruno", null),
        com.mcosta.mybooks.social.model.FeedBook("r-$title", null, title, "Author", null),
        progress = 40, updatedAt = kotlin.time.Instant.fromEpochSeconds(1_800_000_000L),
    )

    @Test
    fun friendsTabLoadsTheReadingRow() = runTest {
        fake.makeFriends(ana, bruno)
        fake.reading = listOf(reading("A Relíquia"))
        fake.signInAs(ana)
        val vm = vm()
        assertEquals(listOf("A Relíquia"), vm.state.value.reading.map { it.book.title })
    }

    @Test
    fun everyoneTabHasNoReadingRow() = runTest {
        fake.reading = listOf(reading("A Relíquia"))
        fake.signInAs(ana)
        val vm = vm()
        vm.selectScope(FeedScope.EVERYONE)
        assertTrue(vm.state.value.reading.isEmpty())
    }

    @Test
    fun readingRowFailingStaysHidden() = runTest {
        fake.makeFriends(ana, bruno)
        fake.addEntry(bruno, ActivityKind.STARTED, title = "Dune")
        fake.failReading = true
        fake.signInAs(ana)
        val vm = vm()
        assertTrue(vm.state.value.reading.isEmpty())
        assertEquals(listOf("Dune"), vm.state.value.entries.map { it.book.title })
        assertNull(vm.state.value.message)
        assertNull(vm.state.value.error)
    }

    @Test
    fun itemsAreGrouped() = runTest {
        fake.makeFriends(ana, bruno)
        repeat(3) { fake.addEntry(bruno, ActivityKind.WANT_TO_READ, title = "Book $it") }
        fake.signInAs(ana)
        val vm = vm()
        val group = assertIs<FeedItem.Group>(vm.state.value.items.single())
        assertEquals(3, group.entries.size)
    }

    @Test
    fun shelvesShowBooksInTheLibrary() = runTest {
        fake.makeFriends(ana, bruno)
        val byIsbn = fake.addEntry(bruno, ActivityKind.STARTED, title = "Dune", isbn = "9780441013593")
        val byTitle = fake.addEntry(bruno, ActivityKind.FINISHED, title = "Emma: a novel")
        fake.addEntry(bruno, ActivityKind.STARTED, title = "Unknown")
        repo.save(Book(title = "Dune (Deluxe)", authors = "Frank Herbert", isbn = "9780441013593", status = ReadingStatus.READ))
        repo.save(Book(title = "Emma", authors = "Author", status = ReadingStatus.WISHLIST))
        fake.signInAs(ana)
        val vm = vm()
        assertEquals(mapOf("share-$byIsbn" to ReadingStatus.READ, "share-$byTitle" to ReadingStatus.WISHLIST), vm.state.value.shelves)
    }

    @Test
    fun wantToReadAddsAndUpdatesTheShelf() = runTest {
        fake.makeFriends(ana, bruno)
        val id = fake.addEntry(bruno, ActivityKind.STARTED, title = "Dune", isbn = "9780441013593")
        fake.signInAs(ana)
        val vm = vm()
        vm.wantToRead(vm.state.value.entries.single().book)
        assertEquals(ReadingStatus.TO_READ, vm.state.value.shelves["share-$id"])
        assertEquals("Added to To read", vm.state.value.message)
    }

    /** Review focus: two quick taps (or the same book on two cards) add it once. */
    @Test
    fun wantToReadTwiceAddsOnce() = runTest {
        fake.makeFriends(ana, bruno)
        fake.addEntry(bruno, ActivityKind.STARTED, title = "Dune", isbn = "9780441013593")
        fake.signInAs(ana)
        val vm = vm()
        val book = vm.state.value.entries.single().book
        vm.wantToRead(book)
        vm.wantToBuy(book)
        assertEquals(1, repo.observeBooks(null, "").first().size)
        assertEquals(ReadingStatus.TO_READ, repo.observeBooks(null, "").first().single().status)
    }
}
