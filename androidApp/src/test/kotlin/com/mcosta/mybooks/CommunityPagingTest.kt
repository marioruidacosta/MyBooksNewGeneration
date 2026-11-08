package com.mcosta.mybooks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.mcosta.mybooks.social.model.ActivityKind
import com.mcosta.mybooks.social.model.FeedBook
import com.mcosta.mybooks.social.model.FeedEntry
import com.mcosta.mybooks.social.model.Person
import com.mcosta.mybooks.ui.social.ActivityState
import com.mcosta.mybooks.ui.social.CommunityFeed
import com.mcosta.mybooks.ui.social.FeedCallbacks
import com.mcosta.mybooks.ui.theme.MyBooksTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Paging keeps going when a whole new page joins the group at the end (a friend adding many books to To read). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommunityPagingTest {
    @get:Rule
    val compose = createComposeRule()

    private val marta = Person("m", "marta", "Marta", null)
    private val start = Instant.parse("2026-11-08T12:00:00Z")

    private fun wants(ids: LongRange) = ids.map { id ->
        FeedEntry(id, marta, ActivityKind.WANT_TO_READ, null, null, start - (1000 - id).minutes, FeedBook("b$id", null, "Book $id", "A", null), null, 0, false)
    }.sortedByDescending { it.id }

    @Test
    fun aPageThatJoinsTheLastGroupStillLoadsMore() {
        var loads = 0
        var state by mutableStateOf(ActivityState(signedIn = true, myId = "me", entries = wants(981L..1000L)))
        compose.setContent { MyBooksTheme { CommunityFeed(state, FeedCallbacks(onLoadMore = { loads++ })) } }
        compose.waitForIdle()
        assertEquals(1, loads)
        state = state.copy(entries = state.entries + wants(961L..980L))   // page 2: all of it joins Marta's group
        compose.waitForIdle()
        assertEquals(2, loads)
    }
}
