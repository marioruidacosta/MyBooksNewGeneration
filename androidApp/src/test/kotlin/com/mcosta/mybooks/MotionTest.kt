package com.mcosta.mybooks

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.mcosta.mybooks.ui.components.CountUp
import com.mcosta.mybooks.ui.components.LocalReduceMotion
import com.mcosta.mybooks.ui.components.animatedProgress
import org.junit.Assert.assertEquals
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
}
