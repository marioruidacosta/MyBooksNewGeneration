package com.mcosta.mybooks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mcosta.mybooks.ui.social.SessionExpiredDialog
import com.mcosta.mybooks.ui.theme.MyBooksTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionExpiredDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private var signIn = 0
    private var later = 0

    private fun show() = compose.setContent {
        MyBooksTheme { SessionExpiredDialog(onSignIn = { signIn++ }, onLater = { later++ }) }
    }

    @Test
    fun saysWhyAndThatTheLibraryIsSafe() {
        show()
        compose.onNodeWithText("You were signed out").assertExists()
        compose.onNodeWithText("7 days without use", substring = true).assertExists()
        compose.onNodeWithText("Your library is safe on this phone", substring = true).assertExists()
    }

    @Test
    fun signInLeadsToSigningIn() {
        show()
        compose.onNodeWithText("Sign in").performClick()
        assertEquals(1, signIn)
        assertEquals(0, later)
    }

    @Test
    fun notNowClosesIt() {
        show()
        compose.onNodeWithText("Not now").performClick()
        assertEquals(1, later)
        assertEquals(0, signIn)
    }
}
