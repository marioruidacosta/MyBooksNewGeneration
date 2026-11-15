package com.mcosta.mybooks.ui.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** The question's title: "Add this phone's 3 books to your account?". */
fun addPhoneBooksTitle(count: Int): String =
    if (count == 1) "Add this phone's 1 book to your account?" else "Add this phone's $count books to your account?"

/**
 * "Add this phone's N books to your account?", at an account's first sign-in on this phone or from Settings → Account:
 * "Add them" moves the phone's own books into the account's library, "Not now" leaves them (so does going back; a tap
 * outside does nothing, so the answer is never given by accident).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPhoneBooksDialog(count: Int, onAdd: () -> Unit, onNotNow: () -> Unit) {
    BasicAlertDialog(onDismissRequest = onNotNow, properties = DialogProperties(dismissOnClickOutside = false)) {
        AddPhoneBooksCard(count, onAdd, onNotNow)
    }
}

/** [AddPhoneBooksDialog]'s card, drawn like an alert dialog (on its own for screenshots, which can't draw dialog windows). */
@Composable
fun AddPhoneBooksCard(count: Int, onAdd: () -> Unit, onNotNow: () -> Unit) {
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
            Row(Modifier.padding(top = 24.dp).align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onNotNow) { Text("Not now") }
                TextButton(onClick = onAdd) { Text("Add them") }
            }
        }
    }
}
