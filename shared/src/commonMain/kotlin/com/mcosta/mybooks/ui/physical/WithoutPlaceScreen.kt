package com.mcosta.mybooks.ui.physical

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The physical books with no shelf yet: choose several and put them on one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WithoutPlaceScreen(viewModel: PhysicalLibraryViewModel, onBack: () -> Unit, onOpenBook: (Long) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var picked by remember { mutableStateOf(emptyList<Long>()) }
    var choosing by remember { mutableStateOf(false) }
    PhysicalMessages(state, viewModel, snackbar)
    val books = state.withoutPlace.sortedBy { it.title.lowercase() }
    val selected = picked.filter { id -> books.any { it.id == id } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Without a place") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (selected.isNotEmpty() && state.bookcases.isNotEmpty()) {
                Button(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Put ${selected.size} on a shelf")
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (books.isEmpty()) {
                item { Text("Every physical book has a place.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge) }
            } else if (state.bookcases.isEmpty()) {
                item { Text("Add a bookcase first, then put these books on its shelves.", Modifier.padding(16.dp)) }
            }
            items(books, key = { it.id }) { book ->
                val checked = book.id in picked
                ListItem(
                    headlineContent = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(book.authors, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { picked = if (checked) picked - book.id else picked + book.id },
                            modifier = Modifier.semantics { contentDescription = "Select ${book.title}" },
                        )
                    },
                    modifier = Modifier.clickable { onOpenBook(book.id) },
                )
                HorizontalDivider()
            }
        }
    }
    if (choosing) {
        ShelfPickerDialog(
            state.bookcases, null,
            onPick = { shelf ->
                choosing = false
                viewModel.place(selected, shelf)
                picked = emptyList()
            },
            onDismiss = { choosing = false },
        )
    }
}
