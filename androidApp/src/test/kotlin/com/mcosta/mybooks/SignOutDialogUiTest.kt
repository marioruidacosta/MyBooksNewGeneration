package com.mcosta.mybooks

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mcosta.mybooks.sync.SignOutCheck
import com.mcosta.mybooks.ui.social.KeepLibraryCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Signing out: the library stays unless the person asks to remove it, and only then is told what that would lose. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SignOutDialogUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val box = "Also remove my library from this phone"
    private val ebooks = "Ebook files are only on this phone and will be deleted."
    private val changes = "2 changes haven't reached your account yet. Removing now loses them."

    private fun show(check: SignOutCheck) = compose.setContent {
        KeepLibraryCard(check, onKeep = { calls += "keep" }, onRemove = { calls += "remove" }, onCancel = { calls += "cancel" })
    }

    @Test
    fun aPlainSignOutWarnsOfNothingAndKeepsTheLibrary() {
        show(SignOutCheck(waiting = 2, ebookFiles = true))
        compose.onNodeWithText("Sign out?").assertExists()
        compose.onNodeWithText(box).assertIsOff()
        compose.onNodeWithText(ebooks).assertDoesNotExist()
        compose.onNodeWithText(changes).assertDoesNotExist()
        compose.onNodeWithText("Sign out").performClick()
        assertEquals(listOf("keep"), calls)
    }

    @Test
    fun tickingTheBoxSaysWhatRemovingLosesAndRemoves() {
        show(SignOutCheck(waiting = 2, ebookFiles = true))
        compose.onNodeWithText(box).performClick()
        compose.onNodeWithText(box).assertIsOn()
        compose.onNodeWithText(ebooks).assertExists()
        compose.onNodeWithText(changes).assertExists()
        compose.onNodeWithText("Sign out").assertDoesNotExist()
        compose.onNodeWithText("Remove anyway and sign out").performClick()
        assertEquals(listOf("remove"), calls)
    }

    @Test
    fun removingWithNothingToLoseSaysSo() {
        show(SignOutCheck())
        compose.onNodeWithText(box).performClick()
        compose.onNodeWithText("Remove and sign out").performClick()
        assertEquals(listOf("remove"), calls)
    }

    @Test
    fun untickingTheBoxGoesBackToAPlainSignOut() {
        show(SignOutCheck(ebookFiles = true))
        compose.onNodeWithText(box).performClick()
        compose.onNodeWithText(box).performClick()
        compose.onNodeWithText(ebooks).assertDoesNotExist()
        compose.onNodeWithText("Sign out").performClick()
        assertEquals(listOf("keep"), calls)
    }

    @Test
    fun cancelStaysSignedIn() {
        show(SignOutCheck(ebookFiles = true))
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("cancel"), calls)
    }
}
