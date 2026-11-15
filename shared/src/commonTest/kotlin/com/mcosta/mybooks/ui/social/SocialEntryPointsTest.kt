@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.mcosta.mybooks.ui.social

import com.mcosta.mybooks.library.LibraryId
import com.mcosta.mybooks.data.FakeShareStateDao
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.sharingFor
import com.mcosta.mybooks.socialFor
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds
import com.mcosta.mybooks.social.FakeSocial
import com.mcosta.mybooks.social.SocialConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SocialEntryPointsTest {
    private val engine = MockEngine { respondOk() }

    @Test
    fun socialHiddenWhenNotConfigured() {
        var created = 0
        assertNull(socialFor(SocialConfig("", ""), engine) { _, _ -> created++; FakeSocial().services })
        assertEquals(0, created)
    }

    @Test
    fun socialShownWhenConfigured() {
        var created = 0
        assertNotNull(socialFor(SocialConfig("https://x.supabase.co", "anon"), engine) { _, _ -> created++; FakeSocial().services })
        assertEquals(1, created)
    }

    @Test
    fun sharingNullWhenSocialNotConfigured() = runTest {
        assertNull(sharingFor(LibraryId.Account("ana"), null, FakeShareStateDao(), backgroundScope) { null })
    }

    /** The pieces wired together: a change is uploaded 2 s later once the signed-in user shares. */
    @Test
    fun wiredTrackerChangesArePublished() = runTest {
        val fake = FakeSocial()
        val ana = fake.addUser("ana")
        fake.signInAs(ana)
        val book = Book(id = 1, title = "Dune", status = ReadingStatus.READING)
        val sharing = sharingFor(LibraryId.Account(ana), fake.services, FakeShareStateDao(), backgroundScope) { book }!!
        runCurrent()
        assertEquals(ana, sharing.status.state.value.activeOwner)
        sharing.tracker.changed(null, book)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals("Dune", fake.published.single().first.title)
    }
}
