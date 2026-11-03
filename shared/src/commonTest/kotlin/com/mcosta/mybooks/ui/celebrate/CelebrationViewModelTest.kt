package com.mcosta.mybooks.ui.celebrate

import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.ChallengeRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.FakeChallengeDao
import com.mcosta.mybooks.data.ReadingEvents
import com.mcosta.mybooks.data.SaveResult
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ChallengeProgress
import com.mcosta.mybooks.domain.Pace
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.ui.today
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CelebrationViewModelTest : MainDispatcherTest() {
    private val events = ReadingEvents()
    private val challenges = ChallengeRepository(FakeChallengeDao())
    private val repo = BookRepository(FakeBookDao(), object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
    }, events = events)
    // Marking a book Read dates it today (the real clock), so "this year" is the real one.
    private val year = today().year
    private fun vm() = CelebrationViewModel(repo, challenges, events, timeZone = TimeZone.currentSystemDefault())

    private suspend fun reading(title: String) = (repo.save(Book(title = title, status = ReadingStatus.READING)) as SaveResult.Saved).id

    @Test
    fun finishingABookCelebratesWithoutAGoal() = runTest {
        val vm = vm()
        val id = reading("Dune")
        repo.setStatus(id, ReadingStatus.READ)
        val c = vm.celebration.value!!
        assertEquals("Dune", c.book.title)
        assertEquals(1, c.readThisYear)
        assertNull(c.challenge)
        assertEquals("That's 1 book in $year", c.line())
        vm.rate(5)
        assertEquals(5, repo.getBook(id)!!.rating)
        assertEquals(5, vm.celebration.value!!.book.rating)
        vm.dismiss()
        assertNull(vm.celebration.value)
    }

    @Test
    fun withAGoalItShowsTheChallenge() = runTest {
        challenges.setGoal(year, 1)
        val vm = vm()
        repo.setStatus(reading("Dune"), ReadingStatus.READ)
        val c = vm.celebration.value!!
        assertEquals(ChallengeProgress(year, 1, 1), c.challenge)
        assertEquals(Pace.Complete, c.pace)
        assertEquals("1 of 1 book · Challenge complete!", c.line())
    }

    @Test
    fun lineTexts() {
        val book = Book(title = "X")
        assertEquals("That's 19 books in 2026", Celebration(book, 2026, 19, null, null).line())
        assertEquals(
            "19 of 30 books · 1 book ahead of schedule",
            Celebration(book, 2026, 19, ChallengeProgress(2026, 30, 19), Pace.Ahead(1)).line(),
        )
    }
}
