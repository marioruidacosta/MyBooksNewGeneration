package com.mcosta.mybooks.ui.physical

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.physical.Bookcase
import com.mcosta.mybooks.physical.FoundBook
import com.mcosta.mybooks.physical.MAX_BOOKCASE_NAME
import com.mcosta.mybooks.physical.findInHome
import com.mcosta.mybooks.physical.onShelf
import com.mcosta.mybooks.physical.shelfIds
import com.mcosta.mybooks.ui.library.lentText

internal fun countText(count: Int, one: String, many: String) = if (count == 1) "1 $one" else "$count $many"

/** What a change said, with its Undo. */
@Composable
internal fun PhysicalMessages(state: PhysicalState, viewModel: PhysicalLibraryViewModel, snackbar: SnackbarHostState) {
    LaunchedEffect(state.noteId) {
        val message = state.message ?: return@LaunchedEffect
        val id = state.noteId
        val undo = state.undo
        try {
            val result = snackbar.showSnackbar(message, actionLabel = undo?.let { "Undo" }, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) undo?.invoke()
        } finally {
            // Also when the screen closes: an old Undo must not come back later and revert newer moves.
            viewModel.dismissMessage(id)
        }
    }
}

/** Your home: rooms, each with its bookcases, and a search that says where a book is. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhysicalLibraryScreen(
    viewModel: PhysicalLibraryViewModel,
    onBack: () -> Unit,
    onOpenBookcase: (String) -> Unit,
    onAdd: () -> Unit,
    onOpenUnplaced: () -> Unit,
    onOpenBook: (Long) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf<String?>(null) }
    // Saved: coming back from a book found here shows the same results.
    var query by rememberSaveable { mutableStateOf("") }
    val found = state.books.findInHome(state.bookcases, state.ebookIds, query)
    PhysicalMessages(state, viewModel, snackbar)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Physical library") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.books.isNotEmpty()) {
                item(key = "find") {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        label = { Text("Find a book by title or author") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (query.isNotBlank()) {
                if (found.isEmpty()) {
                    item { Text("No books match your search.", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium) }
                }
                items(found, key = { "found:${it.book.id}" }) { result -> FoundRow(result) { onOpenBook(result.book.id) } }
                return@LazyColumn
            }
            if (state.bookcases.isEmpty()) {
                item {
                    Text(
                        "Recreate the bookcases in your home, then record which shelf each book is on.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            for ((room, cases) in state.rooms) {
                item(key = "room:$room") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(room, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        IconButton(onClick = { renaming = room }) { Icon(Icons.Filled.Edit, "Rename room $room") }
                    }
                }
                items(cases, key = { it.id }) { case -> BookcaseRow(case, state.books) { onOpenBookcase(case.id) } }
            }
            if (state.withoutPlace.isNotEmpty()) {
                item(key = "unplaced") {
                    Card(Modifier.fillMaxWidth().clickable(onClick = onOpenUnplaced)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(countText(state.withoutPlace.size, "book without a place", "books without a place"), style = MaterialTheme.typography.titleSmall)
                                Text("Tap to put them on a shelf", style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                        }
                    }
                }
            }
            item(key = "add") {
                Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text("Add a bookcase") }
            }
        }
    }
    renaming?.let { room ->
        RenameRoomDialog(room, onRename = { viewModel.renameRoom(room, it); renaming = null }, onDismiss = { renaming = null })
    }
}

/** A book found by the search, and where to go to get it. */
@Composable
private fun FoundRow(found: FoundBook, onClick: () -> Unit) {
    val book = found.book
    val where = found.place?.let { place ->
        listOfNotNull(place.location, found.fromLeft?.let { "${ordinal(it)} from the left" }).joinToString(" · ")
    } ?: "Without a place"
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (book.authors.isNotBlank()) {
                Text(book.authors, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(where, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            book.lentTo?.let { Text(lentText(it, book.lentOn), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun BookcaseRow(case: Bookcase, books: List<Book>, onClick: () -> Unit) {
    val shelves = case.shelfIds()
    val count = shelves.sumOf { books.onShelf(it).size }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BookcaseDrawing(case, { books.onShelf(it) }, Modifier.width(56.dp), shelfHeight = 12.dp)
            Column(Modifier.weight(1f)) {
                Text(case.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${countText(shelves.size, "shelf", "shelves")} · ${countText(count, "book", "books")}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

@Composable
private fun RenameRoomDialog(room: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename $room") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_BOOKCASE_NAME) },
                label = { Text("Room name") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onRename(name) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
