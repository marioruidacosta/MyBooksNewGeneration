package com.mcosta.mybooks.ui.social

import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.library.MemoryPreferences
import com.mcosta.mybooks.social.FakeSocial
import com.mcosta.mybooks.social.SocialException
import com.mcosta.mybooks.social.SuggestionAlerts
import com.mcosta.mybooks.social.SuggestionInbox
import com.mcosta.mybooks.social.model.BookSuggestion
import com.mcosta.mybooks.social.model.FeedBook
import com.mcosta.mybooks.social.model.Person
import com.mcosta.mybooks.social.model.ReportReason
import com.mcosta.mybooks.social.socialMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class BookSuggestionsTest : MainDispatcherTest() {
    private val fake = FakeSocial()
    private val ana = fake.addUser("ana")
    private val rui = fake.addUser("rui")
    private val marta = fake.addUser("marta")
    private val dao = FakeBookDao()
    private val repo = BookRepository(dao, object : BookLookupService {
        override suspend fun lookup(isbn: String) = LookupResult.NotFound
    })
    private val scope by lazy { CoroutineScope(SupervisorJob() + mainDispatcher) }

    private fun person(id: String, name: String) = Person(id, name.lowercase(), name, null)

    private fun suggestion(id: Long, from: Person = person(rui, "Rui"), title: String = "Stoner", note: String = "", seen: Boolean = false) =
        BookSuggestion(
            id, from, FeedBook("suggestion:$id", if (id == 1L) "9780141439587" else "978$id", title, "John Williams", null), note,
            Instant.fromEpochMilliseconds(1_800_000_000_000 + id), seen,
        )

    private fun give(to: String, vararg items: BookSuggestion) {
        fake.suggestionsFor.getOrPut(to) { mutableListOf() } += items
    }

    // ---- the inbox ----

    @Test
    fun signingInLoadsTheSuggestionsAndTheBadge() = runTest {
        give(ana, suggestion(1), suggestion(2, seen = true))
        fake.signInAs(ana)
        val inbox = SuggestionInbox(fake.services, scope)
        assertEquals(listOf(1L, 2L), inbox.items.value.map { it.id })
        assertEquals(1, inbox.unseen.value)
    }

    @Test
    fun signingOutEmptiesIt() = runTest {
        give(ana, suggestion(1))
        fake.signInAs(ana)
        val inbox = SuggestionInbox(fake.services, scope)
        fake.services.auth.signOut()
        assertTrue(inbox.items.value.isEmpty())
        assertEquals(0, inbox.unseen.value)
    }

    @Test
    fun anotherAccountNeverSeesTheFirstOnes() = runTest {
        give(ana, suggestion(1))
        give(rui, suggestion(2, from = person(ana, "Ana")))
        fake.signInAs(ana)
        val inbox = SuggestionInbox(fake.services, scope)
        fake.signInAs(rui)
        assertEquals(listOf(2L), inbox.items.value.map { it.id })
    }

    @Test
    fun aFailedRefreshKeepsWhatIsShown() = runTest {
        give(ana, suggestion(1))
        fake.signInAs(ana)
        val inbox = SuggestionInbox(fake.services, scope)
        fake.failSuggestions = true
        inbox.refresh()
        assertEquals(listOf(1L), inbox.items.value.map { it.id })
    }

    @Test
    fun seeingThemClearsTheBadgeAndTellsTheServer() = runTest {
        give(ana, suggestion(1), suggestion(2))
        fake.signInAs(ana)
        val inbox = SuggestionInbox(fake.services, scope)
        inbox.markSeen()
        advanceUntilIdle()
        assertEquals(0, inbox.unseen.value)
        assertEquals(1, fake.suggestionsSeenCalls)
        inbox.markSeen()
        advanceUntilIdle()
        assertEquals(1, fake.suggestionsSeenCalls, "nothing new to mark")
    }

    @Test
    fun closingRemovesIt() = runTest {
        give(ana, suggestion(1), suggestion(2))
        fake.signInAs(ana)
        val inbox = SuggestionInbox(fake.services, scope)
        assertTrue(inbox.close(1))
        assertEquals(listOf(2L), inbox.items.value.map { it.id })
        assertEquals(listOf(1L), fake.closedSuggestions)
    }

    @Test
    fun aFailedCloseBringsItBackInItsPlace() = runTest {
        give(ana, suggestion(2), suggestion(1))
        fake.signInAs(ana)
        val inbox = SuggestionInbox(fake.services, scope)
        fake.failSuggestions = true
        assertFalse(inbox.close(2))
        assertEquals(listOf(2L, 1L), inbox.items.value.map { it.id })
    }

    @Test
    fun aRefreshUnderWayDoesNotBringBackADismissedSuggestion() = runTest {
        give(ana, suggestion(1))
        fake.signInAs(ana)
        val inbox = SuggestionInbox(fake.services, scope)
        advanceUntilIdle()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fake.suggestionsGate = gate
        scope.launch { inbox.refresh() }
        advanceUntilIdle()
        fake.suggestionsGate = null
        assertTrue(inbox.close(1))
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(inbox.items.value.isEmpty())
    }

    @Test
    fun seeingThemMarksOnlyTheOnesShown() = runTest {
        give(ana, suggestion(1))
        fake.signInAs(ana)
        val inbox = SuggestionInbox(fake.services, scope)
        advanceUntilIdle()
        fake.suggestionsFor.getValue(ana).add(0, suggestion(2))
        inbox.markSeen()
        advanceUntilIdle()
        assertEquals(mapOf(2L to false, 1L to true), fake.suggestionsFor.getValue(ana).associate { it.id to it.seen })
    }

    @Test
    fun oneArrivingWhileMarkingSeenStaysNew() = runTest {
        give(ana, suggestion(1))
        fake.signInAs(ana)
        val inbox = SuggestionInbox(fake.services, scope)
        advanceUntilIdle()
        fake.suggestionsFor.getValue(ana).add(0, suggestion(2))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fake.suggestionsGate = gate
        scope.launch { inbox.refresh() }
        advanceUntilIdle()
        fake.suggestionsGate = null
        inbox.markSeen()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(2L, 1L), inbox.items.value.map { it.id })
        assertEquals(1, inbox.unseen.value)
    }

    @Test
    fun aRefreshDuringACloseDoesNotBringItBack() = runTest {
        give(ana, suggestion(1))
        fake.signInAs(ana)
        val inbox = SuggestionInbox(fake.services, scope)
        advanceUntilIdle()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fake.closeGate = gate
        scope.launch { inbox.close(1) }
        advanceUntilIdle()
        inbox.refresh()
        assertTrue(inbox.items.value.isEmpty())
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(inbox.items.value.isEmpty())
    }

    @Test
    fun aFailedCloseDuringARefreshShowsItOnce() = runTest {
        give(ana, suggestion(1))
        fake.signInAs(ana)
        val inbox = SuggestionInbox(fake.services, scope)
        advanceUntilIdle()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fake.closeGate = gate
        scope.launch { inbox.close(1) }
        advanceUntilIdle()
        inbox.refresh()
        fake.failSuggestions = true
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(1L), inbox.items.value.map { it.id })
    }

    @Test
    fun acceptingKeepsThePagesAndTheBlurb() = runTest {
        val rich = suggestion(1).let { it.copy(book = it.book.copy(pages = 288, blurb = "A quiet life.")) }
        give(ana, rich)
        fake.signInAs(ana)
        val inbox = SuggestionInbox(fake.services, scope)
        val vm = vm(inbox)
        advanceUntilIdle()
        vm.acceptSuggestion(inbox.items.value.single(), ReadingStatus.TO_READ)
        advanceUntilIdle()
        val added = repo.observeBooks(null, "").first().single()
        assertEquals(288, added.pageCount)
        assertEquals("A quiet life.", added.description)
    }

    @Test
    fun aNoteIsNeverCutInsideAnEmoji() {
        assertEquals("x".repeat(279), fitNote("x".repeat(279) + "😀"))
        assertEquals("x".repeat(278) + "😀", fitNote("x".repeat(278) + "😀y"))
        assertEquals("short", fitNote("short"))
    }

    @Test
    fun theDailyLimitSaysSo() {
        assertEquals(
            "You've suggested a lot of books today. Try again tomorrow.",
            socialMessage(SocialException(SocialException.Kind.TOO_MANY, "limit")),
        )
    }

    // ---- the notification ----

    @Test
    fun aNotificationIsOnlyForTheAccountStillSignedIn() = runTest {
        give(ana, suggestion(1))
        fake.signInAs(ana)
        val alerts = SuggestionAlerts(fake.services, MemoryPreferences())
        val alert = assertNotNull(alerts.check())
        assertTrue(alerts.isCurrent(alert))
        fake.services.auth.signOut()
        assertFalse(alerts.isCurrent(alert))
    }

    @Test
    fun signingOutEndsTheAccount() = runTest {
        fake.signInAs(ana)
        val alerts = SuggestionAlerts(fake.services, MemoryPreferences())
        var ended = 0
        val job = scope.launch { alerts.accountEnded.collect { ended++ } }
        advanceUntilIdle()
        assertEquals(0, ended)
        fake.services.auth.signOut()
        advanceUntilIdle()
        assertEquals(1, ended)
        job.cancel()
    }

    @Test
    fun theBackgroundCheckRunsOnlyForSomeoneSignedInWithItOn() = runTest {
        val alerts = SuggestionAlerts(fake.services, MemoryPreferences())
        assertEquals(false, alerts.shouldRun.first())
        fake.signInAs(ana)
        assertEquals(true, alerts.shouldRun.first())
        alerts.setEnabled(false)
        assertEquals(false, alerts.shouldRun.first())
    }

    @Test
    fun oneNewSuggestionNamesTheFriendAndTheBook() = runTest {
        give(ana, suggestion(1, note = "You'd love this"))
        fake.signInAs(ana)
        val alert = assertNotNull(SuggestionAlerts(fake.services, MemoryPreferences()).check())
        assertEquals("Rui suggested a book", alert.title)
        assertEquals("Stoner: “You'd love this”", alert.text)
    }

    @Test
    fun severalNewSuggestionsAreCounted() = runTest {
        give(ana, suggestion(3), suggestion(2, from = person(marta, "Marta")), suggestion(1, from = person("x", "Zé")))
        fake.signInAs(ana)
        val alert = assertNotNull(SuggestionAlerts(fake.services, MemoryPreferences()).check())
        assertEquals("3 new book suggestions", alert.title)
        assertEquals("From Rui, Marta and 1 more", alert.text)
    }

    @Test
    fun eachSuggestionIsNotifiedOnce() = runTest {
        give(ana, suggestion(1))
        fake.signInAs(ana)
        val alerts = SuggestionAlerts(fake.services, MemoryPreferences())
        alerts.notified(assertNotNull(alerts.check()))
        assertNull(alerts.check())
        give(ana, suggestion(2))
        assertEquals("Rui suggested a book", alerts.check()?.title)
    }

    @Test
    fun whatWasAlreadySeenIsNotNotified() = runTest {
        give(ana, suggestion(1, seen = true))
        fake.signInAs(ana)
        assertNull(SuggestionAlerts(fake.services, MemoryPreferences()).check())
    }

    @Test
    fun turnedOffOrSignedOutNothingIsNotified() = runTest {
        give(ana, suggestion(1))
        val prefs = MemoryPreferences()
        val alerts = SuggestionAlerts(fake.services, prefs)
        assertNull(alerts.check(), "signed out")
        fake.signInAs(ana)
        assertTrue(alerts.enabled, "on by default")
        alerts.setEnabled(false)
        assertNull(alerts.check())
        alerts.setEnabled(true)
        assertNotNull(alerts.check())
    }

    @Test
    fun eachAccountKeepsItsOwnPlace() = runTest {
        give(ana, suggestion(5))
        give(rui, suggestion(3, from = person(ana, "Ana")))
        val alerts = SuggestionAlerts(fake.services, MemoryPreferences())
        fake.signInAs(ana)
        alerts.notified(assertNotNull(alerts.check()))
        fake.signInAs(rui)
        assertNotNull(alerts.check(), "a lower id, but this account hasn't been told")
    }

    // ---- sending ----

    @Test
    fun whatSendingSays() {
        val a = person("a", "Ana")
        val b = person("b", "Rui")
        assertEquals("Suggested to Ana", suggestionSentText(1, listOf(a)))
        assertEquals("Suggested to 2 friends", suggestionSentText(2, listOf(a, b)))
        assertEquals("Suggested to 1 of 2 friends", suggestionSentText(1, listOf(a, b)))
        assertEquals("You've already suggested this to Ana", suggestionSentText(0, listOf(a)))
        assertEquals("You've already suggested this to them", suggestionSentText(0, listOf(a, b)))
    }

    // ---- answering ----

    private fun vm(inbox: SuggestionInbox) = ActivityViewModel(fake.services, repo, mainDispatcher, inbox)

    @Test
    fun wantToReadAddsTheBookAndClosesTheSuggestion() = runTest {
        give(ana, suggestion(1))
        fake.signInAs(ana)
        val inbox = SuggestionInbox(fake.services, scope)
        val vm = vm(inbox)
        vm.acceptSuggestion(inbox.items.value.single(), ReadingStatus.TO_READ)
        advanceUntilIdle()
        val added = repo.observeBooks(null, "").first().single()
        assertEquals("Stoner", added.title)
        assertEquals(ReadingStatus.TO_READ, added.status)
        assertEquals(listOf(1L), fake.closedSuggestions)
        assertTrue(inbox.items.value.isEmpty())
        assertEquals("Added to To read", vm.state.value.message)
    }

    @Test
    fun aSuggestionWithAMalformedIsbnCanStillBeAdded() = runTest {
        give(ana, suggestion(2))
        fake.signInAs(ana)
        val inbox = SuggestionInbox(fake.services, scope)
        val vm = vm(inbox)
        vm.acceptSuggestion(inbox.items.value.single(), ReadingStatus.TO_READ)
        advanceUntilIdle()
        val added = repo.observeBooks(null, "").first().single()
        assertEquals("Stoner", added.title)
        assertEquals(null, added.isbn)
        assertEquals(listOf(2L), fake.closedSuggestions)
    }

    @Test
    fun toBuyAddsItToTheWishlist() = runTest {
        give(ana, suggestion(1))
        fake.signInAs(ana)
        val inbox = SuggestionInbox(fake.services, scope)
        vm(inbox).acceptSuggestion(inbox.items.value.single(), ReadingStatus.WISHLIST)
        advanceUntilIdle()
        assertEquals(ReadingStatus.WISHLIST, repo.observeBooks(null, "").first().single().status)
    }

    @Test
    fun aBookAlreadyInTheLibraryIsNotAddedTwice() = runTest {
        repo.save(Book(isbn = "9780141439587", title = "Stoner", authors = "John Williams", status = ReadingStatus.READ))
        give(ana, suggestion(1))
        fake.signInAs(ana)
        val inbox = SuggestionInbox(fake.services, scope)
        val vm = vm(inbox)
        vm.acceptSuggestion(inbox.items.value.single(), ReadingStatus.TO_READ)
        advanceUntilIdle()
        val books = repo.observeBooks(null, "").first()
        assertEquals(1, books.size)
        assertEquals(ReadingStatus.READ, books.single().status)
        assertTrue(inbox.items.value.isEmpty())
        assertEquals("Already in your library", vm.state.value.message)
    }

    @Test
    fun dismissingClosesItWithoutAddingTheBook() = runTest {
        give(ana, suggestion(1))
        fake.signInAs(ana)
        val inbox = SuggestionInbox(fake.services, scope)
        vm(inbox).dismissSuggestion(inbox.items.value.single())
        advanceUntilIdle()
        assertTrue(repo.observeBooks(null, "").first().isEmpty())
        assertEquals(listOf(1L), fake.closedSuggestions)
    }

    @Test
    fun aFailedDismissSaysSoAndKeepsIt() = runTest {
        give(ana, suggestion(1))
        fake.signInAs(ana)
        val inbox = SuggestionInbox(fake.services, scope)
        val vm = vm(inbox)
        fake.failSuggestions = true
        vm.dismissSuggestion(inbox.items.value.single())
        advanceUntilIdle()
        assertEquals(1, inbox.items.value.size)
        assertEquals("Couldn't dismiss the suggestion", vm.state.value.message)
    }

    @Test
    fun aReportNamesTheSenderAndKeepsTheirNote() = runTest {
        give(ana, suggestion(1, note = "buy my thing"))
        fake.signInAs(ana)
        val inbox = SuggestionInbox(fake.services, scope)
        vm(inbox).reportSuggestion(inbox.items.value.single(), ReportReason.SPAM, " spam ")
        advanceUntilIdle()
        val report = fake.reports.single()
        assertEquals(rui, report.reported)
        assertEquals(ReportReason.SPAM, report.reason)
        assertEquals("spam\n\nNote on the suggestion: buy my thing", report.details)
    }
}
