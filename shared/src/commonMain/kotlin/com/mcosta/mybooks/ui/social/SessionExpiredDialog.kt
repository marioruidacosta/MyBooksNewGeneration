package com.mcosta.mybooks.ui.social

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** The session ended after a week without use: says so, so that the phone's empty library isn't taken for a lost one. */
@Composable
fun SessionExpiredDialog(onSignIn: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("You were signed out") },
        text = { Text("MyBooks signs you out after 7 days without use. Your library is safe on this phone and comes back when you sign in.") },
        confirmButton = { TextButton(onClick = onSignIn) { Text("Sign in") } },
        dismissButton = { TextButton(onClick = onLater) { Text("Not now") } },
    )
}
