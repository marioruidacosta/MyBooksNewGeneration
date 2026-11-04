package com.mcosta.mybooks.ui.activity

import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.ChallengeRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.FakeChallengeDao
import com.mcosta.mybooks.data.backup.BackupSerializer
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.Challenge
import com.mcosta.mybooks.domain.ChallengeProgress
import com.mcosta.mybooks.domain.Pace
import com.mcosta.mybooks.domain.ReadingStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReadingActivityViewModelTest : MainDispatcherTest() {
    private val utc = TimeZone.UTC
    private fun day(text: String) = LocalDate.parse(text).atStartOfDayIn(utc).toEpochMilliseconds()
    private val challenges = ChallengeRepository(FakeChallengeDao())
    private val repo = BookRepository(FakeBookDao(), object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
    })

    /** Restored from a backup: adding a book as Read in the app would date it today. */
    private suspend fun read(title: String, date: String?) =
        repo.importJson(BackupSerializer.encode(listOf(Book(title = title, status = ReadingStatus.READ, dateRead = date?.let(::day))), 0))

    private fun vm() = ReadingActivityViewModel(repo, challenges, today = { LocalDate.parse("2026-11-03") }, timeZone = utc)

    @Test
    fun currentYearPastYearsAndBooksWithoutDate() = runTest {
        read("Oct", "2026-10-24")
        read("Jan", "2026-01-10")
        read("Old", "2025-06-01")
        read("No date", null)
        challenges.setGoal(2026, 30)
        challenges.setGoal(2025, 1)
        challenges.setGoal(2027, 40)
        val vm = vm()
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        val s = vm.state.value
        assertEquals(YearChallenge(ChallengeProgress(2026, 30, 2), Pace.Behind(23)), s.current)
        assertEquals(listOf("Oct", "Jan"), s.readThisYear.map { it.title })
        assertEquals(
            listOf(YearChallenge(ChallengeProgress(2027, 40, 0), Pace.Upcoming), YearChallenge(ChallengeProgress(2025, 1, 1), Pace.Complete)),
            s.others,
        )
        assertEquals(1, s.withoutDate)
    }

    @Test
    fun setEditAndRemoveGoals() = runTest {
        val vm = vm()
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        assertNull(vm.state.value.current)
        assertNull(vm.setGoal("2026", "30"))
        assertEquals(30, vm.state.value.current?.progress?.goal)
        assertNull(vm.setGoal(" 2026 ", "35 "))                       // editing replaces the goal
        assertEquals(listOf(Challenge(2026, 35)), challenges.all())
        vm.removeGoal(2026)
        assertNull(vm.state.value.current)
    }

    @Test
    fun invalidGoalsExplainWhy() = runTest {
        val vm = vm()
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        assertEquals("Enter a number of books from 1 to 999", vm.setGoal("2026", "0"))
        assertEquals("Enter a number of books from 1 to 999", vm.setGoal("2026", "lots"))
        assertEquals("Enter a year up to 2027", vm.setGoal("2028", "10"))
        assertEquals("Enter a year up to 2027", vm.setGoal("abc", "10"))
        assertNull(vm.setGoal("2027", "10"))
        assertEquals(listOf(Challenge(2027, 10)), challenges.all())
    }

    @Test
    fun yearListsAndBooksWithoutDate() = runTest {
        read("Oct", "2026-10-24")
        read("Old", "2025-06-01")
        read("No date", null)
        val year = ReadYearViewModel(repo, 2025, utc)
        val undated = ReadYearViewModel(repo, null, utc)
        backgroundScope.launch(mainDispatcher) { year.books.collect {} }
        backgroundScope.launch(mainDispatcher) { undated.books.collect {} }
        assertEquals(listOf("Old"), year.books.value?.map { it.title })
        assertEquals(listOf("No date"), undated.books.value?.map { it.title })
    }

    @Test
    fun texts() {
        assertEquals("2 books ahead of schedule", Pace.Ahead(2).text())
        assertEquals("1 book behind schedule", Pace.Behind(1).text())
        assertEquals("On track", Pace.OnTrack.text())
        assertEquals("Ended 3 books short", Pace.Ended(3).text())
        assertEquals("Challenge complete!", Pace.Complete.text())
        assertEquals("Starts on 1 January", Pace.Upcoming.text())
        assertEquals("18 of 30 books", ChallengeProgress(2026, 30, 18).countText())
    }

    @Test
    fun statisticsForThisYearOrAChosenOne() = runTest {
        read("Oct", "2026-10-24")
        read("Old", "2025-06-01")
        val vm = vm()
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        assertEquals(listOf(2026, 2025), vm.state.value.statsYears)
        assertEquals(2026, vm.state.value.stats?.year)
        assertEquals(1, vm.state.value.stats?.books)
        assertEquals(1, vm.state.value.stats?.previousYearBooks)
        vm.showStats(2025)
        assertEquals(2025, vm.state.value.stats?.year)
        assertEquals(1, vm.state.value.stats?.perMonth?.get(5))
    }
}
