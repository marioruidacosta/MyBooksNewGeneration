package com.mcosta.mybooks

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import okio.Path.Companion.toPath
import com.mcosta.mybooks.ui.components.CountUp
import com.mcosta.mybooks.ui.components.LocalReduceMotion
import com.mcosta.mybooks.ui.components.animatedProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The app's small animations: they end in the right state, and show it at once when the phone's animations are off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MotionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun progressEndsAtTheLatestValue() {
        compose.mainClock.autoAdvance = false
        var target by mutableFloatStateOf(0.2f)
        var shown = -1f
        compose.setContent { shown = animatedProgress(target) }
        compose.mainClock.advanceTimeBy(30)
        target = 0.6f
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(30)
        target = 0.9f
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(300)
        assertEquals(0.9f, shown, 0.001f)
    }

    @Test
    fun countUpEndsAtTheValue() {
        compose.mainClock.autoAdvance = false
        compose.setContent { CountUp(37, TextStyle.Default) }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithText("37").assertExists()
    }

    @Test
    fun reducedMotionShowsTheEndStateAtOnce() {
        compose.mainClock.autoAdvance = false
        var shown = -1f
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                shown = animatedProgress(0.9f)
                CountUp(37, TextStyle.Default)
                Text("ready")
            }
        }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(0.9f, shown, 0.001f)
        compose.onNodeWithText("37").assertExists()
    }

    /** A cover that can't be loaded: the shimmer gives way to the generated cover (it never shimmers forever). */
    @Test
    fun aFailedCoverEndsTheShimmer() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.setContent {
            CompositionLocalProvider(
                com.mcosta.mybooks.ui.LocalCoverStore provides com.mcosta.mybooks.data.covers.CoverStore(okio.FileSystem.SYSTEM, okio.Path.Companion.run { context.filesDir.absolutePath.toPath() / "covers" }),
            ) {
                com.mcosta.mybooks.ui.components.BookCover(
                    "file:///does/not/exist.jpg",
                    androidx.compose.ui.Modifier.size(80.dp, 120.dp),
                    title = "Stoner",
                )
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Stoner").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("cover-shimmer").assertDoesNotExist()
    }

    /** While a cover loads, its place shimmers. */
    @Test
    fun theLoadingCoverShimmers() {
        compose.setContent { com.mcosta.mybooks.ui.components.CoverLoading(androidx.compose.ui.Modifier.size(80.dp, 120.dp)) }
        compose.onNodeWithTag("cover-shimmer").assertExists()
    }

    /** The status highlight glides: its weight moves from 0 to 1 over a quarter second, not at once. */
    @Test
    fun theSelectionHighlightGlides() {
        compose.mainClock.autoAdvance = false
        var selected by androidx.compose.runtime.mutableStateOf(false)
        var shown = -1f
        compose.setContent { shown = com.mcosta.mybooks.ui.components.selectionProgress(selected) }
        compose.mainClock.advanceTimeByFrame()
        selected = true
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(100)
        assertTrue("mid-way: $shown", shown > 0f && shown < 1f)
        compose.mainClock.advanceTimeBy(300)
        assertEquals(1f, shown, 0.001f)
    }

    /** Tapping a star pops it: bigger for a moment, then back to its size. */
    @Test
    fun aStarPopsThenSettles() {
        compose.mainClock.autoAdvance = false
        var rating by androidx.compose.runtime.mutableStateOf<Int?>(null)
        var scale = -1f
        compose.setContent { scale = com.mcosta.mybooks.ui.components.rememberPop(rating) }
        compose.mainClock.advanceTimeByFrame()
        rating = 3
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(80)
        assertTrue("popped: $scale", scale > 1.05f)
        compose.mainClock.advanceTimeBy(500)
        assertEquals(1f, scale, 0.001f)
    }

    @Test
    fun noGlideOrPopWithReducedMotion() {
        compose.mainClock.autoAdvance = false
        var on by androidx.compose.runtime.mutableStateOf(false)
        var shown = -1f
        var scale = -1f
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                shown = com.mcosta.mybooks.ui.components.selectionProgress(on)
                scale = com.mcosta.mybooks.ui.components.rememberPop(if (on) 3 else null)
            }
        }
        compose.mainClock.advanceTimeByFrame()
        on = true
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1f, shown, 0.001f)
        assertEquals(1f, scale, 0.001f)
    }

    /** Finishing a book: a burst of confetti that goes away by itself. */
    @Test
    fun confettiBurstsThenGoes() {
        compose.mainClock.autoAdvance = false
        compose.setContent { com.mcosta.mybooks.ui.components.Confetti(androidx.compose.ui.Modifier.size(200.dp)) }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("confetti").assertExists()
        compose.mainClock.advanceTimeBy(1600)
        compose.onNodeWithTag("confetti").assertDoesNotExist()
    }

    @Test
    fun noConfettiWithReducedMotion() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                com.mcosta.mybooks.ui.components.Confetti(androidx.compose.ui.Modifier.size(200.dp))
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("confetti").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("confetti").assertDoesNotExist()
    }
}
