package com.mcosta.mybooks.ui.home

import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.ChallengeRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.FakeChallengeDao
import com.mcosta.mybooks.data.SaveResult
import com.mcosta.mybooks.data.backup.BackupSerializer
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ChallengeProgress
import com.mcosta.mybooks.domain.Pace
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.ui.components.CoverRef
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeViewModelTest : MainDispatcherTest() {
    private val utc = TimeZone.UTC
    private fun day(text: String) = LocalDate.parse(text).atStartOfDayIn(utc).toEpochMilliseconds()
    private val challenges = ChallengeRepository(FakeChallengeDao())
    private val repo = BookRepository(FakeBookDao(), object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
    })

    private fun vm() = HomeViewModel(repo, challenges, today = { LocalDate.parse("2026-11-03") }, timeZone = utc)

    @Test
    fun readingMostRecentlyStartedFirst() = runTest {
        repo.save(Book(title = "Old", status = ReadingStatus.READING, dateStarted = day("2026-01-01")))
        repo.save(Book(title = "Undated", status = ReadingStatus.READING))
        repo.save(Book(title = "New", status = ReadingStatus.READING, dateStarted = day("2026-10-30")))
        repo.save(Book(title = "Done", status = ReadingStatus.READ, dateRead = day("2026-10-01")))
        val vm = vm()
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        // "Undated" got today's (real) start date when saved as Reading, so it's the newest.
        assertEquals(listOf("Undated", "New", "Old"), vm.state.value.reading.map { it.title })
    }

    @Test
    fun shelvesCountBooksAndFanTheNewestCovers() = runTest {
        for (i in 1..7) repo.save(Book(title = "T$i", status = ReadingStatus.TO_READ, coverUrl = "c$i".takeIf { i != 6 }))
        repo.save(Book(title = "Buy", status = ReadingStatus.WISHLIST))
        repo.save(Book(title = "R", status = ReadingStatus.READ, coverUrl = "r", dateRead = day("2026-02-02")))
        val vm = vm()
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        val shelves = vm.state.value.shelves
        assertEquals(listOf(ReadingStatus.TO_READ, ReadingStatus.WISHLIST, ReadingStatus.READ, null), shelves.map { it.status })
        assertEquals(listOf(7, 1, 1, 9), shelves.map { it.count })
        // The newest books, with or without a cover picture (those get a generated cover).
        assertEquals(SHELF_COVERS, shelves[0].covers.size)
        assertEquals(listOf(CoverRef(null, "Buy", "")), shelves[1].covers)
        assertEquals(CoverRef("r", "R", ""), shelves[2].covers.single())
    }

    @Test
    fun thisYearsChallenge() = runTest {
        repo.save(Book(title = "A", status = ReadingStatus.READ, dateRead = day("2026-03-01")))
        repo.save(Book(title = "B", status = ReadingStatus.READ, dateRead = day("2025-12-31")))
        // Restored without a read date (adding it as Read in the app would date it today): counts toward no year.
        repo.importJson(BackupSerializer.encode(listOf(Book(title = "C", status = ReadingStatus.READ)), 0))
        val vm = vm()
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        assertNull(vm.state.value.challenge)
        assertEquals(2026, vm.state.value.year)
        challenges.setGoal(2026, 30)
        assertEquals(ChallengeProgress(2026, 30, 1), vm.state.value.challenge)
        assertEquals(Pace.Behind(24), vm.state.value.pace)
    }

    @Test
    fun quickUpdateToTheLastPageAsksIfFinished() = runTest {
        val id = (repo.save(Book(title = "Dune", status = ReadingStatus.READING, pageCount = 688)) as SaveResult.Saved).id
        val vm = vm()
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        assertEquals(true, vm.updateProgress(id, "300", byPages = true))
        assertEquals(43, repo.getBook(id)!!.progressPercent)
        assertNull(vm.finishPrompt.value)
        assertEquals(true, vm.updateProgress(id, "688", byPages = true))
        assertEquals("Dune", vm.finishPrompt.value?.title)
        vm.markFinished()
        assertNull(vm.finishPrompt.value)
        assertEquals(ReadingStatus.READ, repo.getBook(id)!!.status)
    }

    @Test
    fun quickUpdateByPercentAndNotYetFinished() = runTest {
        val id = (repo.save(Book(title = "Emma", status = ReadingStatus.READING)) as SaveResult.Saved).id
        val vm = vm()
        backgroundScope.launch(mainDispatcher) { vm.state.collect {} }
        assertEquals(false, vm.updateProgress(id, "150", byPages = false))
        assertEquals("Enter a percentage from 0 to 100", vm.progressError.value)
        assertEquals(true, vm.updateProgress(id, "100", byPages = false))
        assertNull(vm.progressError.value)
        assertEquals("Emma", vm.finishPrompt.value?.title)
        vm.dismissFinish()
        assertNull(vm.finishPrompt.value)
        assertEquals(ReadingStatus.READING, repo.getBook(id)!!.status)
    }
}
