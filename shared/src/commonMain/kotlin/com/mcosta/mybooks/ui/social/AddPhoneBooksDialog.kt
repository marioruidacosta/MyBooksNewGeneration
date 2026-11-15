package com.mcosta.mybooks.ui.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.mcosta.mybooks.library.Libraries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The question's title: "Add this phone's 3 books to your account?". */
fun addPhoneBooksTitle(count: Int): String =
    if (count == 1) "Add this phone's 1 book to your account?" else "Add this phone's $count books to your account?"

/** Shown when "Add them" failed (nothing is lost; it can be tried again). */
const val ADD_PHONE_BOOKS_FAILED = "Couldn't add this phone's books. Try again."

/**
 * The phone's books, app-wide: the first sign-in's "Add this phone's N books to your account?" over whatever screen is
 * open (its account's sync waits for the answer), and "Moving your books…" while any move into an account runs.
 */
@Composable
fun PhoneBooksPrompts(libraries: Libraries<*>) {
    val offer by libraries.offer.collectAsState()
    val moving by libraries.moving.collectAsState()
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    val question = offer
    when {
        moving -> MovingBooksDialog()
        question != null -> AddPhoneBooksDialog(
            question.count,
            error = if (failed) ADD_PHONE_BOOKS_FAILED else null,
            onAdd = {
                failed = false
                scope.launch {
                    failed = try {
                        libraries.addPhoneBooks(question.account)
                        false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        true
                    }
                }
            },
            onNotNow = {
                failed = false
                libraries.notNow()
            },
        )
    }
}

/**
 * "Add this phone's N books to your account?", at an account's first sign-in on this phone or from Settings → Account:
 * "Add them" moves the phone's own books into the account's library, "Not now" leaves them (so does going back; a tap
 * outside does nothing, so the answer is never given by accident). [error] says the last try failed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPhoneBooksDialog(count: Int, onAdd: () -> Unit, onNotNow: () -> Unit, error: String? = null) {
    BasicAlertDialog(onDismissRequest = onNotNow, properties = DialogProperties(dismissOnClickOutside = false)) {
        AddPhoneBooksCard(count, onAdd, onNotNow, error)
    }
}

/** [AddPhoneBooksDialog]'s card, drawn like an alert dialog (on its own for screenshots, which can't draw dialog windows). */
@Composable
fun AddPhoneBooksCard(count: Int, onAdd: () -> Unit, onNotNow: () -> Unit, error: String? = null) {
    Surface(
        shape = AlertDialogDefaults.shape,
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
    ) {
        Column(Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 18.dp)) {
            Text(
                addPhoneBooksTitle(count),
                style = MaterialTheme.typography.headlineSmall,
                color = AlertDialogDefaults.titleContentColor,
            )
            Text(
                "They'll move into your account's library and sync to your other devices.",
                style = MaterialTheme.typography.bodyMedium,
                color = AlertDialogDefaults.textContentColor,
                modifier = Modifier.padding(top = 16.dp),
            )
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Row(Modifier.padding(top = 24.dp).align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onNotNow) { Text("Not now") }
                TextButton(onClick = onAdd) { Text("Add them") }
            }
        }
    }
}

/** While the phone's books move: it can't be dismissed, and goes once they're in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovingBooksDialog() {
    BasicAlertDialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        MovingBooksCard()
    }
}

/** [MovingBooksDialog]'s card (on its own for screenshots). */
@Composable
fun MovingBooksCard() {
    Surface(
        shape = AlertDialogDefaults.shape,
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
    ) {
        Column(Modifier.padding(24.dp)) {
            Text(
                "Moving your books…",
                style = MaterialTheme.typography.headlineSmall,
                color = AlertDialogDefaults.titleContentColor,
            )
            Text(
                "This phone's books are moving into your account's library.",
                style = MaterialTheme.typography.bodyMedium,
                color = AlertDialogDefaults.textContentColor,
                modifier = Modifier.padding(top = 16.dp),
            )
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 24.dp))
        }
    }
}
