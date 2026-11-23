package com.mcosta.mybooks.ui.physical

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.physical.Bookcase
import com.mcosta.mybooks.physical.MAX_BOOKCASE_NAME
import com.mcosta.mybooks.physical.MAX_COLUMNS
import com.mcosta.mybooks.physical.MAX_SHELVES
import com.mcosta.mybooks.physical.newBookcase
import com.mcosta.mybooks.physical.shelfIds
import com.mcosta.mybooks.physical.withColumnAdded
import com.mcosta.mybooks.physical.withShelfAdded
import com.mcosta.mybooks.physical.withoutColumn
import com.mcosta.mybooks.physical.withoutShelf

private const val NEW_COLUMNS = 2
private const val NEW_SHELVES = 4

private data class Removal(val title: String, val confirm: String, val count: Int, val apply: () -> Unit)

/** A new bookcase ([bookcaseId] null) or an existing one. Nothing is saved until Save; the drawing follows. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookcaseEditScreen(viewModel: PhysicalLibraryViewModel, bookcaseId: String?, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val original = remember(bookcaseId) { bookcaseId?.let(viewModel::bookcase) }
    var draft by remember(bookcaseId) { mutableStateOf(original ?: newBookcase("", "", NEW_COLUMNS, NEW_SHELVES)) }
    var name by remember(bookcaseId) { mutableStateOf(original?.name.orEmpty()) }
    var room by remember(bookcaseId) { mutableStateOf(original?.room.orEmpty()) }
    var freed by remember(bookcaseId) { mutableStateOf(emptySet<String>()) }
    var asking by remember { mutableStateOf<Removal?>(null) }
    val readOnly = original?.readOnly == true

    fun removeShelves(title: String, confirm: String, shelves: List<String>, apply: () -> Unit) {
        val count = viewModel.countOn(shelves)
        val done = { freed = freed + shelves; apply() }
        if (count == 0) done() else asking = Removal(title, confirm, count, done)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (original == null) "New bookcase" else "Edit bookcase") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (readOnly) {
                Text("Made by a newer version of MyBooks. Update the app to change it.", color = MaterialTheme.colorScheme.error)
            }
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(MAX_BOOKCASE_NAME) },
                label = { Text("Name") }, singleLine = true, enabled = !readOnly, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = room, onValueChange = { room = it.take(MAX_BOOKCASE_NAME) },
                label = { Text("Room") }, singleLine = true, enabled = !readOnly, modifier = Modifier.fillMaxWidth(),
            )
            val otherRooms = state.rooms.map { it.first }.filterNot { it.equals(room.trim(), ignoreCase = true) }
            if (otherRooms.isNotEmpty() && !readOnly) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    otherRooms.forEach { AssistChip(onClick = { room = it }, label = { Text(it) }) }
                }
            }
            if (!readOnly) {
                if (original == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Stepper(
                            "Columns", draft.columns.size, "columns",
                            onLess = { draft = draft.withoutColumn(draft.columns.lastIndex) },
                            onMore = { draft = draft.withColumnAdded(draft.columns.first().shelves.size) },
                            canLess = draft.columns.size > 1, canMore = draft.columns.size < MAX_COLUMNS,
                            modifier = Modifier.weight(1f),
                        )
                        val shelves = draft.columns.first().shelves.size
                        Stepper(
                            "Shelves", shelves, "shelves",
                            onLess = { draft = draft.copy(columns = draft.columns.map { it.copy(shelves = it.shelves.dropLast(1)) }) },
                            onMore = { draft = draft.columns.indices.fold(draft) { case, c -> case.withShelfAdded(c) } },
                            canLess = shelves > 1, canMore = shelves < MAX_SHELVES,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    draft.columns.forEachIndexed { c, column ->
                        val letter = 'A' + c
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Column $letter · ${countText(column.shelves.size, "shelf", "shelves")}", modifier = Modifier.weight(1f))
                            IconButton(
                                enabled = column.shelves.size > 1,
                                onClick = {
                                    val last = column.shelves.last()
                                    removeShelves("Remove this shelf?", "Remove shelf", listOf(last)) { draft = draft.withoutShelf(last) }
                                },
                            ) { Icon(Icons.Filled.Remove, "Remove a shelf from column $letter") }
                            IconButton(
                                enabled = column.shelves.size < MAX_SHELVES,
                                onClick = { draft = draft.withShelfAdded(c) },
                            ) { Icon(Icons.Filled.Add, "Add a shelf to column $letter") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = draft.columns.size < MAX_COLUMNS,
                            onClick = { draft = draft.withColumnAdded(draft.columns.last().shelves.size) },
                        ) { Text("Add a column") }
                        OutlinedButton(
                            enabled = draft.columns.size > 1,
                            onClick = {
                                val last = draft.columns.lastIndex
                                removeShelves("Remove this column?", "Remove column", draft.columns[last].shelves) {
                                    draft = draft.withoutColumn(last)
                                }
                            },
                        ) { Text("Remove last column") }
                    }
                }
            }
            BookcaseDrawing(draft, viewModel::booksOn, Modifier.fillMaxWidth(), labels = true, shelfHeight = 44.dp)
            if (!readOnly) {
                Button(
                    enabled = name.isNotBlank() && room.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        viewModel.saveBookcase(draft.copy(name = name.trim(), room = room.trim()), freed)
                        onDone()
                    },
                ) { Text("Save bookcase") }
            }
            if (original != null) {
                TextButton(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    onClick = {
                        asking = Removal("Delete this bookcase?", "Delete", viewModel.countOn(original.shelfIds())) {
                            viewModel.removeBookcase(original.id)
                            onDone()
                        }
                    },
                ) { Text("Delete bookcase", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    asking?.let { removal ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(removal.title) },
            text = {
                Text(
                    if (removal.count == 0) "No books are on it."
                    else "${countText(removal.count, "book", "books")} will go to Without a place.",
                )
            },
            confirmButton = { TextButton(onClick = { asking = null; removal.apply() }) { Text(removal.confirm) } },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Stepper(
    label: String,
    value: Int,
    what: String,
    onLess: () -> Unit,
    onMore: () -> Unit,
    canLess: Boolean,
    canMore: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = canLess, onClick = onLess) { Icon(Icons.Filled.Remove, "Fewer $what") }
            Text("$value", style = MaterialTheme.typography.titleMedium)
            IconButton(enabled = canMore, onClick = onMore) { Icon(Icons.Filled.Add, "More $what") }
        }
    }
}
