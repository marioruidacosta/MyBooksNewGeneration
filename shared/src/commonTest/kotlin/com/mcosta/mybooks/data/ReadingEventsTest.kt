package com.mcosta.mybooks.data

import com.mcosta.mybooks.data.backup.BackupSerializer
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadingEventsTest {
    private val events = ReadingEvents()
    private val repo = BookRepository(FakeBookDao(), object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
    }, events = events)

    @Test
    fun becomingReadIsAnnouncedOnce() = runTest {
        val heard = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { events.finished.toList(heard) }
        val id = (repo.save(Book(title = "Dune", status = ReadingStatus.READING)) as SaveResult.Saved).id
        repo.setStatus(id, ReadingStatus.READ)
        repo.setStatus(id, ReadingStatus.READ)                                   // already Read: nothing new
        val other = (repo.save(Book(title = "Emma")) as SaveResult.Saved).id
        repo.save(repo.getBook(other)!!.copy(status = ReadingStatus.READ))        // the edit screen
        repo.save(Book(title = "Added as read", status = ReadingStatus.READ))     // a new book: not a finish
        repo.importJson(BackupSerializer.encode(listOf(Book(title = "Restored", status = ReadingStatus.READ)), 0))
        assertEquals(listOf(id, other), heard)
    }
}
