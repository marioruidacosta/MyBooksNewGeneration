package com.mcosta.mybooks.social

import com.mcosta.mybooks.library.MemoryPreferences
import com.mcosta.mybooks.social.model.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdleSessionTest {
    private val day = 24L * 60 * 60 * 1000
    private var now = 100 * day
    private val prefs = MemoryPreferences()
    private val session = MutableStateFlow<SessionState>(SessionState.SignedIn("u1", "ana@test.local"))
    private var ended = 0

    private fun TestScope.idle(whenReady: suspend () -> Unit = {}) = IdleSession(prefs, session, backgroundScope, { now }, whenReady) {
        ended++
        session.value = SessionState.SignedOut
    }

    /** The app was used [ago] milliseconds ago. */
    private fun TestScope.usedAgo(ago: Long, idle: IdleSession) {
        val today = now
        now = today - ago
        idle.left()
        now = today
    }

    @Test
    fun aRecentSessionStays() = runTest {
        val idle = idle()
        usedAgo(6 * day, idle)
        assertFalse(idle.endIfIdle())
        assertEquals(0, ended)
        assertFalse(idle.expired.value)
    }

    @Test
    fun exactlySevenDaysIsStillAllowed() = runTest {
        val idle = idle()
        usedAgo(7 * day, idle)
        assertFalse(idle.endIfIdle())
    }

    @Test
    fun aSessionUnusedForSevenDaysEnds() = runTest {
        val idle = idle()
        usedAgo(7 * day + 1, idle)
        assertTrue(idle.endIfIdle())
        assertEquals(1, ended)
        assertTrue(idle.expired.value)
    }

    @Test
    fun theFirstRunAfterTheUpdateStartsTheClock() = runTest {
        val idle = idle()
        assertFalse(idle.endIfIdle())
        idle.opened()
        runCurrent()
        now += 7 * day + 1
        assertTrue(idle.endIfIdle())
    }

    @Test
    fun aClockMovedBackDoesNotEndIt() = runTest {
        val idle = idle()
        usedAgo(-30 * day, idle)
        assertFalse(idle.endIfIdle())
    }

    @Test
    fun openingTheAppRestartsTheClock() = runTest {
        val idle = idle()
        usedAgo(6 * day, idle)
        idle.opened()
        runCurrent()
        now += 6 * day
        assertFalse(idle.endIfIdle())
    }

    @Test
    fun comingBackAfterTheLimitEndsItInsteadOfCounting() = runTest {
        val idle = idle()
        usedAgo(8 * day, idle)
        idle.opened()
        runCurrent()
        assertEquals(1, ended)
    }

    @Test
    fun leavingAfterTheLimitDoesNotHideIt() = runTest {
        val idle = idle()
        usedAgo(8 * day, idle)
        idle.left()
        assertTrue(idle.endIfIdle())
    }

    @Test
    fun signingInRestartsTheClockAndClearsTheNotice() = runTest {
        val idle = idle()
        usedAgo(8 * day, idle)
        idle.endIfIdle()
        idle.signingIn()
        session.value = SessionState.SignedIn("u1", "ana@test.local")
        assertFalse(idle.expired.value)
        assertFalse(idle.endIfIdle())
    }

    @Test
    fun nobodySignedInNothingEnds() = runTest {
        session.value = SessionState.SignedOut
        val idle = idle()
        usedAgo(30 * day, idle)
        assertFalse(idle.endIfIdle())
        assertEquals(0, ended)
    }

    @Test
    fun theNoticeSurvivesARestart() = runTest {
        val first = idle()
        usedAgo(8 * day, first)
        first.endIfIdle()
        assertTrue(idle().expired.value)
        idle().dismissExpired()
        assertFalse(idle().expired.value)
    }

    @Test
    fun twoChecksAtOnceEndItOnce() = runTest {
        val idle = idle()
        usedAgo(8 * day, idle)
        val a = async { idle.endIfIdle() }
        val b = async { idle.endIfIdle() }
        assertEquals(setOf(true, false), setOf(a.await(), b.await()))
        assertEquals(1, ended)
    }

    @Test
    fun aFailedEndIsTriedAgain() = runTest {
        var fail = true
        val idle = IdleSession(prefs, session, backgroundScope, { now }) {
            if (fail) error("no connection")
            ended++
        }
        usedAgo(8 * day, idle)
        assertFalse(idle.endIfIdle())
        assertFalse(idle.expired.value)
        fail = false
        assertTrue(idle.endIfIdle())
        assertEquals(1, ended)
    }

    @Test
    fun itWaitsForTheLibraryToOpen() = runTest {
        val open = CompletableDeferred<Unit>()
        val idle = idle(whenReady = { open.await() })
        usedAgo(8 * day, idle)
        val check = async { idle.endIfIdle() }
        runCurrent()
        assertEquals(0, ended)
        open.complete(Unit)
        assertTrue(check.await())
        assertEquals(1, ended)
    }

    @Test
    fun itWaitsToKnowWhoIsSignedIn() = runTest {
        session.value = SessionState.Loading
        val idle = idle()
        usedAgo(8 * day, idle)
        val check = async { idle.endIfIdle() }
        runCurrent()
        assertEquals(0, ended)
        session.value = SessionState.SignedIn("u1", "ana@test.local")
        assertTrue(check.await())
    }
}
