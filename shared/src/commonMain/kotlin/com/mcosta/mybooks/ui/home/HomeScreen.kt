package com.mcosta.mybooks.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.ui.activity.countText
import com.mcosta.mybooks.ui.activity.text
import com.mcosta.mybooks.ui.components.BookCover
import com.mcosta.mybooks.ui.components.ProgressField
import com.mcosta.mybooks.ui.components.CoverFan
import com.mcosta.mybooks.ui.detail.progressText
import com.mcosta.mybooks.ui.library.label
import com.mcosta.mybooks.data.suggestions.Suggestion
import com.mcosta.mybooks.ui.suggestions.SuggestedForYou
import com.mcosta.mybooks.ui.suggestions.SuggestionsState

/** A shelf's name on the home screen. */
fun ReadingStatus?.shelfLabel(): String = if (this == null) "All books" else label()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenBook: (Long) -> Unit,
    /** A shelf's list ([ReadingStatus] null is All books). */
    onOpenShelf: (ReadingStatus?) -> Unit,
    onOpenReadingActivity: () -> Unit,
    /** "Suggested for you" (state from [com.mcosta.mybooks.ui.suggestions.SuggestionsViewModel]). */
    suggestions: SuggestionsState = SuggestionsState(loading = false),
    onOpenSuggestion: (Suggestion) -> Unit = {},
    onNotInterested: (String) -> Unit = {},
    onSeeAllSuggestions: () -> Unit = {},
    onAddManually: () -> Unit,
    onScan: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var addMenuOpen by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf<Book?>(null) }
    val progressError by viewModel.progressError.collectAsStateWithLifecycle()
    val finishPrompt by viewModel.finishPrompt.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("MyBooks", style = MaterialTheme.typography.headlineSmall) })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { addMenuOpen = true }) { Icon(Icons.Filled.Add, "Add book") }
                DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Scan barcode") },
                        leadingIcon = { Icon(Icons.Filled.QrCodeScanner, null) },
                        onClick = { addMenuOpen = false; onScan() },
                    )
                    DropdownMenuItem(
                        text = { Text("Enter manually") },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = { addMenuOpen = false; onAddManually() },
                    )
                }
            }
        },
    ) { padding ->
        if (!state.loaded) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                if (state.reading.isEmpty()) {
                    SectionTitle("Reading")
                } else {
                    SectionTitle("Reading · ${state.reading.size}", onClick = { onOpenShelf(ReadingStatus.READING) })
                }
            }
            item {
                if (state.reading.isEmpty()) {
                    OutlinedCard(
                        onClick = { onOpenShelf(ReadingStatus.TO_READ) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        Text(
                            "Nothing on the go. Pick your next book from To read.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(state.reading, key = { it.id }) { book -> ReadingCard(book, onClick = { onOpenBook(book.id) }, onUpdate = { updating = book }) }
                    }
                }
            }
            item {
                ChallengeCard(state, onClick = onOpenReadingActivity, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp))
            }
            item { SuggestedForYou(suggestions, onOpenSuggestion, onNotInterested, onSeeAllSuggestions) }
            item { SectionTitle("Shelves") }
            items(state.shelves, key = { it.status?.name ?: "ALL" }) { shelf ->
                ShelfRow(shelf, onClick = { onOpenShelf(shelf.status) })
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
    }

    updating?.let { book ->
        ProgressSheet(
            book,
            error = progressError,
            onSave = { input, byPages -> if (viewModel.updateProgress(book.id, input, byPages)) updating = null },
            onDismiss = { updating = null; viewModel.clearProgressError() },
        )
    }
    finishPrompt?.let { book ->
        AlertDialog(
            onDismissRequest = viewModel::dismissFinish,
            title = { Text("Finished?") },
            text = { Text("You're at the end of 201c${book.title}201d. Mark it as Read, finished today?") },
            confirmButton = { TextButton(onClick = viewModel::markFinished) { Text("Mark as Read") } },
            dismissButton = { TextButton(onClick = viewModel::dismissFinish) { Text("Not yet") } },
        )
    }
}

/** A section heading; with [onClick] it opens the section's full list. */
@Composable
private fun SectionTitle(text: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "See all")
    }
}

/** A book being read: a large cover, its title and progress. */
@Composable
private fun ReadingCard(book: Book, onClick: () -> Unit, onUpdate: () -> Unit) {
    // A tap opens the book; a long press updates its progress.
    Column(
        Modifier.width(112.dp).combinedClickable(onClick = onClick, onLongClick = onUpdate, onLongClickLabel = "Update progress"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(shape = RoundedCornerShape(6.dp), shadowElevation = 4.dp, modifier = Modifier.width(112.dp).height(168.dp)) {
            BookCover(book.coverUrl, Modifier.fillMaxSize(), title = book.title, authors = book.authors)
        }
        val progress = book.progressPercent
        if (progress != null) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            Text("$progress%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Quick progress update: a page or a percentage, as on the book page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgressSheet(book: Book, error: String?, onSave: (input: String, byPages: Boolean) -> Unit, onDismiss: () -> Unit) {
    var byPages by remember(book.id) { mutableStateOf(book.pageCount != null) }
    var input by remember(book.id) { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                progressText(book) ?: "No progress yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProgressField(input, { input = it }, hint = if (byPages) "Page" else "%", isError = error != null, onDone = { onSave(input, byPages) })
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    SegmentedButton(selected = byPages, onClick = { byPages = true }, shape = SegmentedButtonDefaults.itemShape(0, 2), label = { Text("Pages") })
                    SegmentedButton(selected = !byPages, onClick = { byPages = false }, shape = SegmentedButtonDefaults.itemShape(1, 2), label = { Text("%") })
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = { onSave(input, byPages) }, enabled = input.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
}

/** This year's challenge, or an invitation to set one. */
@Composable
private fun ChallengeCard(state: HomeState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Your reading activity", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            val challenge = state.challenge
            if (challenge == null) {
                Text("Set your ${state.year} reading goal", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Choose how many books you want to read this year and follow your progress.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("${challenge.year} Reading Challenge", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(challenge.countText(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("${challenge.percent}%", style = MaterialTheme.typography.bodyMedium)
                }
                LinearProgressIndicator(progress = { challenge.percent / 100f }, modifier = Modifier.fillMaxWidth())
                state.pace?.let { Text(it.text(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** A shelf: its name and size, with its newest covers fanned out. */
@Composable
private fun ShelfRow(shelf: Shelf, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(shelf.status.shelfLabel(), style = MaterialTheme.typography.titleMedium)
            Text(
                if (shelf.count == 1) "1 book" else "${shelf.count} books",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CoverFan(shelf.covers)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.padding(start = 8.dp))
    }
}
