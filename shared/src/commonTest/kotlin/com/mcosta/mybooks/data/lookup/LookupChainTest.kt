package com.mcosta.mybooks.data.lookup

import com.mcosta.mybooks.domain.Book
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlinx.io.IOException

class LookupChainTest {
    private class Fixed(val result: LookupResult) : BookLookupService {
        var calls = 0
        override suspend fun lookup(isbn: String): LookupResult { calls++; return result }
    }

    private val withCover = LookupResult.Found(Book(title = "Dune", coverUrl = "https://bnp/cover.jpg"))
    private val noCover = LookupResult.Found(Book(title = "Dune", authors = "Frank Herbert"))
    private val otherWithCover = LookupResult.Found(Book(title = "Dune (Google)", coverUrl = "https://google/cover.jpg"))
    private val error = LookupResult.Error(IOException("offline"))
    private val miss = LookupResult.NotFound

    @Test
    fun firstHitWithCoverSkipsTheRest() = runTest {
        val second = Fixed(otherWithCover)
        assertEquals(withCover, LookupChain(listOf(Fixed(withCover), second)).lookup("x"))
        assertEquals(0, second.calls)
    }

    @Test
    fun missesFallThroughInOrder() = runTest {
        val third = Fixed(otherWithCover)
        assertEquals(otherWithCover, LookupChain(listOf(Fixed(miss), Fixed(error), third)).lookup("x"))
        assertEquals(1, third.calls)
    }

    @Test
    fun hitWithoutCoverTakesCoverFromLaterSource() = runTest {
        val result = LookupChain(listOf(Fixed(noCover), Fixed(miss), Fixed(otherWithCover))).lookup("x")
        val book = (result as LookupResult.Found).book
        assertEquals("Dune", book.title)
        assertEquals("Frank Herbert", book.authors)
        assertEquals("https://google/cover.jpg", book.coverUrl)
    }

    @Test
    fun hitWithoutCoverStaysWithoutWhenNobodyHasOne() = runTest {
        assertEquals(noCover, LookupChain(listOf(Fixed(noCover), Fixed(error), Fixed(noCover))).lookup("x"))
    }

    @Test
    fun allMissIsNotFound() = runTest {
        assertEquals(miss, LookupChain(listOf(Fixed(miss), Fixed(miss), Fixed(miss))).lookup("x"))
    }

    @Test
    fun missAndErrorIsNotFoundWithErrors() = runTest {
        assertEquals(
            LookupResult.NotFoundWithErrors(error.cause),
            LookupChain(listOf(Fixed(miss), Fixed(error), Fixed(miss))).lookup("x"),
        )
    }

    @Test
    fun allErrorIsError() = runTest {
        assertTrue(LookupChain(listOf(Fixed(error), Fixed(error))).lookup("x") is LookupResult.Error)
    }

    /** Answers [result] after [millis] (virtual time), counting how many lookups are running at once. */
    private class Slow(val result: LookupResult, val millis: Long, val running: IntArray) : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult {
            running[0]++
            running[1] = maxOf(running[1], running[0])
            delay(millis)
            running[0]--
            return result
        }
    }

    @Test
    fun coverAsksAllSourcesAtOnce() = runTest {
        val running = IntArray(2)
        val chain = LookupChain(listOf(Slow(miss, 1000, running), Slow(error, 1000, running), Slow(otherWithCover, 1000, running)))
        val start = testScheduler.currentTime
        assertEquals("https://google/cover.jpg", chain.lookupCover("x")?.coverUrl)
        assertEquals(3, running[1])
        assertEquals(1000, testScheduler.currentTime - start)        // the slowest source, not the sum
    }

    @Test
    fun coverPrefersEarlierSourcesEvenWhenSlower() = runTest {
        val running = IntArray(2)
        val chain = LookupChain(listOf(Slow(withCover, 2000, running), Slow(otherWithCover, 100, running)))
        assertEquals("https://bnp/cover.jpg", chain.lookupCover("x")?.coverUrl)
    }

    @Test
    fun coverDoesNotWaitForLaterSourcesOnceAnEarlierOneHasIt() = runTest {
        val running = IntArray(2)
        val chain = LookupChain(listOf(Slow(withCover, 100, running), Slow(otherWithCover, 5000, running)))
        val start = testScheduler.currentTime
        assertEquals("https://bnp/cover.jpg", chain.lookupCover("x")?.coverUrl)
        assertEquals(100, testScheduler.currentTime - start)
    }

    @Test
    fun noCoverAnywhere() = runTest {
        assertEquals(null, LookupChain(listOf(Fixed(noCover), Fixed(miss), Fixed(error))).lookupCover("x"))
    }

    @Test
    fun descriptionFromTheFirstSourceThatHasOne() = runTest {
        val bnp = Fixed(LookupResult.Found(Book(title = "Dune", coverUrl = "https://bnp/cover.jpg")))
        val google = Fixed(LookupResult.Found(Book(title = "Dune", description = "Desert planet.")))
        val openLibrary = Fixed(LookupResult.Found(Book(title = "Dune", description = "Other text.")))
        assertEquals("Desert planet.", LookupChain(listOf(bnp, google, openLibrary)).lookupDescription("x"))
        assertEquals(null, LookupChain(listOf(Fixed(miss), Fixed(error), Fixed(noCover))).lookupDescription("x"))
    }
}
