package com.mcosta.mybooks.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.ui.components.BookCover

/** Covers per row in the book grids. */
private const val GRID_COLUMNS = 4

/** The goal being edited: a year with a goal, or a new challenge (year chosen in the dialog). */
private data class GoalEdit(val year: Int, val goal: Int?, val isNew: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingActivityScreen(
    viewModel: ReadingActivityViewModel,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
    /** The books read in a year; null for read books without a read date. */
    onOpenYear: (Int?) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<GoalEdit?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your reading activity") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        if (!state.loaded) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                CurrentChallenge(
                    state,
                    onSet = { editing = GoalEdit(state.year, state.current?.progress?.goal, isNew = false) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (state.withoutDate > 0) {
                item {
                    OutlinedCard(onClick = { onOpenYear(null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${state.withoutDate} read ${if (state.withoutDate == 1) "book has" else "books have"} no read " +
                                    "date, so ${if (state.withoutDate == 1) "it doesn't" else "they don't"} count toward any year. " +
                                    "Open one to set its date.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                        }
                    }
                }
            }
            item { Heading("Read in ${state.year} · ${state.readThisYear.size}") }
            if (state.readThisYear.isEmpty()) {
                item { Hint("No books finished this year yet.") }
            } else {
                items(state.readThisYear.chunked(GRID_COLUMNS)) { row -> CoverRow(row, onOpenBook) }
            }
            item { Heading("Challenges") }
            if (state.others.isEmpty()) item { Hint("Your other years' challenges will be listed here.") }
            items(state.others, key = { it.progress.year }) { challenge ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenYear(challenge.progress.year) }.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${challenge.progress.year} · ${challenge.progress.countText()}", style = MaterialTheme.typography.titleSmall)
                        Text(challenge.pace.text(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { editing = GoalEdit(challenge.progress.year, challenge.progress.goal, isNew = false) }) {
                        Icon(Icons.Filled.Edit, "Edit ${challenge.progress.year} goal")
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            item {
                OutlinedButton(
                    onClick = { editing = GoalEdit(state.year + 1, null, isNew = true) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Filled.Add, null)
                    Text("  New challenge")
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    editing?.let { edit ->
        GoalDialog(
            edit = edit,
            onSave = { year, goal -> viewModel.setGoal(year, goal).also { if (it == null) editing = null } },
            onRemove = if (edit.goal != null) ({ viewModel.removeGoal(edit.year); editing = null }) else null,
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun CurrentChallenge(state: ReadingActivityState, onSet: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${state.year} Reading Challenge", style = MaterialTheme.typography.titleLarge)
            val current = state.current
            if (current == null) {
                Text(
                    "Choose how many books you want to read in ${state.year}. Books you mark Read with a read date " +
                        "in ${state.year} count toward it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onSet) { Text("Set ${state.year} goal") }
            } else {
                Text(current.progress.countText(), style = MaterialTheme.typography.headlineSmall)
                LinearProgressIndicator(progress = { current.progress.percent / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(current.pace.text(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${current.progress.percent}%", style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = onSet) { Text("Edit goal") }
            }
        }
    }
}

/** Sets or edits a year's goal; [onSave] returns the problem to show, or null when saved. */
@Composable
private fun GoalDialog(edit: GoalEdit, onSave: (year: String, goal: String) -> String?, onRemove: (() -> Unit)?, onDismiss: () -> Unit) {
    var year by remember { mutableStateOf(edit.year.toString()) }
    var goal by remember { mutableStateOf(edit.goal?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (edit.isNew) "New challenge" else "${edit.year} reading goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (edit.isNew) {
                    OutlinedTextField(
                        value = year,
                        onValueChange = { year = it.filter(Char::isDigit).take(4); error = null },
                        label = { Text("Year") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                OutlinedTextField(
                    value = goal,
                    onValueChange = { goal = it.filter(Char::isDigit).take(3); error = null },
                    label = { Text("Books to read") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { error = onSave(year, goal) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/** A row of the cover grid (the last row may be short). */
@Composable
internal fun CoverRow(books: List<Book>, onOpenBook: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (book in books) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                shadowElevation = 3.dp,
                modifier = Modifier.weight(1f).aspectRatio(2f / 3f).clickable { onOpenBook(book.id) },
            ) {
                BookCover(book.coverUrl, Modifier.fillMaxSize(), title = book.title, authors = book.authors)
            }
        }
        repeat(GRID_COLUMNS - books.size) { Spacer(Modifier.weight(1f)) }
    }
}
