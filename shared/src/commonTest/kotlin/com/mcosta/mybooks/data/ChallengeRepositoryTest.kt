package com.mcosta.mybooks.data

import com.mcosta.mybooks.data.backup.BackupSerializer
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.Challenge
import com.mcosta.mybooks.domain.ReadingStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ChallengeRepositoryTest {
    private val challenges = ChallengeRepository(FakeChallengeDao())
    private val repo = BookRepository(FakeBookDao(), object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
    }, clock = { 1000L }, challenges = challenges)

    @Test
    fun oneGoalPerYearNewestFirst() = runTest {
        challenges.setGoal(2025, 25)
        challenges.setGoal(2026, 30)
        challenges.setGoal(2026, 35)
        assertEquals(listOf(Challenge(2026, 35), Challenge(2025, 25)), challenges.all())
        challenges.remove(2025)
        assertEquals(listOf(Challenge(2026, 35)), challenges.all())
    }

    @Test
    fun goalMustBeBetweenOneAndMax() = runTest {
        assertFailsWith<IllegalArgumentException> { challenges.setGoal(2026, 0) }
        assertFailsWith<IllegalArgumentException> { challenges.setGoal(2026, 1000) }
    }

    @Test
    fun backupKeepsChallenges() = runTest {
        challenges.setGoal(2026, 30)
        challenges.setGoal(2025, 25)
        val backup = repo.exportJson()
        assertEquals(listOf(Challenge(2026, 30), Challenge(2025, 25)), BackupSerializer.decodeChallenges(backup))

        val restored = ChallengeRepository(FakeChallengeDao())
        val other = BookRepository(FakeBookDao(), object : BookLookupService {
            override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
        }, challenges = restored)
        restored.setGoal(2026, 12)                       // replaced by the backup's goal
        restored.setGoal(2024, 10)                       // a year the backup doesn't have stays
        other.importJson(backup)
        assertEquals(listOf(Challenge(2026, 30), Challenge(2025, 25), Challenge(2024, 10)), restored.all())
    }

    @Test
    fun olderBackupsWithoutChallengesStillImport() = runTest {
        val old = """{"version":1,"exportedAt":0,"books":[{"title":"Dune","status":"READ"}]}"""
        assertEquals(ImportSummary(1, 0, 0), repo.importJson(old))
        assertEquals(emptyList(), challenges.all())
    }

    @Test
    fun readDateCanBeSetAndDropsALaterStartDate() = runTest {
        val id = (repo.save(Book(title = "Dune", status = ReadingStatus.READING, dateStarted = 500L)) as SaveResult.Saved).id
        repo.setStatus(id, ReadingStatus.READ)
        repo.setDateRead(id, 9000L)
        assertEquals(9000L, repo.getBook(id)!!.dateRead)
        assertEquals(500L, repo.getBook(id)!!.dateStarted)
        repo.setDateRead(id, 400L)
        assertEquals(400L, repo.getBook(id)!!.dateRead)
        assertNull(repo.getBook(id)!!.dateStarted)
    }

    @Test
    fun readDateBeforeStartDateDropsTheStartDate() = runTest {
        val id = (repo.save(Book(title = "Dune", status = ReadingStatus.READ, dateRead = 900L, dateStarted = 500L)) as SaveResult.Saved).id
        repo.setDateRead(id, 100L)
        assertEquals(100L, repo.getBook(id)!!.dateRead)
        assertNull(repo.getBook(id)!!.dateStarted)
    }

    @Test
    fun backupKeepsNotInterestedSuggestions() = runTest {
        val hidden = com.mcosta.mybooks.data.suggestions.HiddenSuggestions(null)
        hidden.hide("9789722120005")
        val source = BookRepository(FakeBookDao(), object : BookLookupService {
            override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
        }, hiddenSuggestions = hidden)
        val restoredHidden = com.mcosta.mybooks.data.suggestions.HiddenSuggestions(null)
        restoredHidden.hide("other")
        val target = BookRepository(FakeBookDao(), object : BookLookupService {
            override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
        }, hiddenSuggestions = restoredHidden)
        target.importJson(source.exportJson())
        assertEquals(setOf("9789722120005", "other"), restoredHidden.keys.value)
    }
}
