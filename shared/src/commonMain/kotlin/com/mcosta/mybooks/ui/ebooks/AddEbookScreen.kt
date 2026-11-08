package com.mcosta.mybooks.ui.ebooks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.data.ebooks.Match
import com.mcosta.mybooks.ui.components.BookCover
import com.mcosta.mybooks.ui.library.label

const val ADOBE_MESSAGE = "This book is protected with Adobe DRM. Read it in Adobe Digital Editions; MyBooks can still track it."
const val PROTECTED_MESSAGE = "This file is protected and can't be opened"
const val UNREADABLE_MESSAGE = "This file can't be opened as a book"

/** Adding an ebook file: the book it goes with, then Read now or Add to To read (or, if protected, why not). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEbookScreen(viewModel: AddEbookViewModel, onBack: () -> Unit, onDone: (bookId: Long, read: Boolean) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(state) { (state as? AddEbookState.Done)?.let { onDone(it.bookId, it.read) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add an ebook") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = state) {
                AddEbookState.Checking, AddEbookState.Working, is AddEbookState.Done -> Busy(if (s == AddEbookState.Checking) "Checking the file…" else "Adding the book…")
                is AddEbookState.Ready -> {
                    BookLine(s.match, s.format.name)
                    val ask = { action: () -> Unit -> if (s.replaces) confirm = action else action() }
                    Button(onClick = { ask(viewModel::readNow) }, modifier = Modifier.fillMaxWidth()) { Text("Read now") }
                    OutlinedButton(onClick = { ask(viewModel::addToRead) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (s.match is Match.New) "Add to To read" else "Add the file to this book")
                    }
                }
                is AddEbookState.AdobeProtected -> Protected(ADOBE_MESSAGE, s.match, viewModel::addWithoutFile)
                is AddEbookState.Protected -> Protected(PROTECTED_MESSAGE, s.match, viewModel::addWithoutFile)
                AddEbookState.Unreadable -> {
                    Text(UNREADABLE_MESSAGE, style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = onBack) { Text("Back") }
                }
                is AddEbookState.Failed -> {
                    Text(s.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onBack) { Text("Back") }
                }
            }
        }
    }

    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Replace the ebook file?") },
            text = { Text("This book already has an ebook file. The new one replaces it, and reading starts from the beginning.") },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Busy(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The book a file goes with: its cover, title, authors, and whether it is new or already in the library. */
@Composable
private fun BookLine(match: Match, format: String?) {
    val b = match.book
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        BookCover(b.coverUrl, Modifier.width(72.dp).height(108.dp), title = b.title, authors = b.authors)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(b.title, style = MaterialTheme.typography.titleLarge)
            if (b.authors.isNotBlank()) Text(b.authors, style = MaterialTheme.typography.bodyMedium)
            Text(
                listOfNotNull(format, if (match is Match.New) "New book" else "Already in ${b.status.label()}").joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun Protected(message: String, match: Match, onAdd: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Lock, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.error)
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
    BookLine(match, null)
    Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
        Text(if (match is Match.New) "Add to library without the file" else "Keep tracking it without the file")
    }
}
