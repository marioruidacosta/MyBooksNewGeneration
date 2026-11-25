package com.mcosta.mybooks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.mcosta.mybooks.ui.components.LAUNCH_INTRO_TAG
import com.mcosta.mybooks.ui.components.LaunchIntro
import com.mcosta.mybooks.ui.theme.MyBooksTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LaunchIntroUiTest {
    @get:Rule
    val compose = createComposeRule()

    private var done = 0

    private fun show(reduceMotion: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent { MyBooksTheme { LaunchIntro(reduceMotion) { done++ } } }
    }

    @Test
    fun theWordmarkShowsThenFadesIntoTheApp() {
        show(reduceMotion = false)
        compose.onNodeWithTag(LAUNCH_INTRO_TAG).assertExists()
        compose.mainClock.advanceTimeBy(1_300)
        assertEquals(0, done)
        compose.mainClock.advanceTimeBy(700)
        assertEquals(1, done)
    }

    @Test
    fun aTapSkipsIt() {
        show(reduceMotion = false)
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag(LAUNCH_INTRO_TAG).performClick()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(1, done)
    }

    @Test
    fun withAnimationsOffItIsBrief() {
        show(reduceMotion = true)
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(1, done)
    }
}
