package com.mcosta.mybooks.ui.scan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.platform.ScanOutcome
import com.mcosta.mybooks.ui.LocalPlatformServices
import com.mcosta.mybooks.ui.components.BookCover
import com.mcosta.mybooks.ui.library.label
import kotlinx.coroutines.launch

/**
 * Scan several books in a row: the scanner opens again after each one until it is closed (Back). Every book found is
 * added to the chosen shelf at once; the list shows what happened to each barcode, with Undo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchScanScreen(
    viewModel: BatchScanViewModel,
    onBack: () -> Unit,
    /** A barcode no book database knows, to type the book in. */
    onAddManually: (isbn: String) -> Unit,
    onOpenBook: (Long) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val services = LocalPlatformServices.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var scanning by rememberSaveable { mutableStateOf(true) }
    val tell = { text: String ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
        Unit
    }

    LaunchedEffect(scanning) {
        while (scanning) {
            when (val outcome = services.scanner.scan()) {
                is ScanOutcome.Isbn -> if (!viewModel.scanned(outcome.isbn)) tell("Already scanned")
                ScanOutcome.NotAnIsbn -> tell("That barcode isn't a book's ISBN")
                ScanOutcome.Cancelled -> scanning = false
                is ScanOutcome.Failed -> {
                    tell("Couldn't open the scanner: ${outcome.message}")
                    scanning = false
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Scan several") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Add the books to", style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val shelves = listOf(ReadingStatus.TO_READ, ReadingStatus.WISHLIST)
                        for ((i, shelf) in shelves.withIndex()) {
                            SegmentedButton(
                                selected = state.status == shelf,
                                onClick = { viewModel.setStatus(shelf) },
                                shape = SegmentedButtonDefaults.itemShape(i, shelves.size),
                                label = { Text(shelf.label()) },
                            )
                        }
                    }
                    Button(onClick = { scanning = true }, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(18.dp))
                        Text(if (state.items.isEmpty()) "Start scanning" else "Scan more", Modifier.padding(start = 8.dp))
                    }
                    Text(
                        when {
                            state.items.isEmpty() -> "The scanner opens again after each book. Press Back in the scanner to stop."
                            state.added == 1 -> "1 book added"
                            else -> "${state.added} books added"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.items, key = { it.isbn }) { item ->
                ScanRow(
                    item,
                    onUndo = { viewModel.undo(item.isbn) },
                    onRetry = { viewModel.retry(item.isbn) },
                    onAddManually = { onAddManually(item.isbn) },
                    onOpenBook = onOpenBook,
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ScanRow(item: ScanItem, onUndo: () -> Unit, onRetry: () -> Unit, onAddManually: () -> Unit, onOpenBook: (Long) -> Unit) {
    val result = item.result
    val book = when (result) {
        is ScanResult.Added -> result.book
        is ScanResult.AlreadyHave -> result.book
        is ScanResult.Undone -> result.book
        else -> null
    }
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = result is ScanResult.Added || result is ScanResult.AlreadyHave) { book?.let { onOpenBook(it.id) } }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book?.coverUrl, Modifier.width(40.dp).height(60.dp), title = book?.title ?: item.isbn, authors = book?.authors.orEmpty())
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(book?.title ?: "ISBN ${item.isbn}", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!book?.authors.isNullOrBlank()) {
                Text(book!!.authors, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                when (result) {
                    ScanResult.Looking -> "Looking it up…"
                    is ScanResult.Added -> "Added to ${result.book.status.label()}"
                    is ScanResult.AlreadyHave -> "Already in ${result.book.status.label()}"
                    ScanResult.NotFound -> "Not found in the book databases"
                    is ScanResult.Failed -> "Couldn't look it up (${result.reason})"
                    is ScanResult.Undone -> "Not added"
                },
                style = MaterialTheme.typography.labelMedium,
                color = when (result) {
                    is ScanResult.Added -> MaterialTheme.colorScheme.primary
                    ScanResult.NotFound, is ScanResult.Failed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        when (result) {
            ScanResult.Looking -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            is ScanResult.Added -> TextButton(onClick = onUndo) { Text("Undo") }
            ScanResult.NotFound -> TextButton(onClick = onAddManually) { Text("Add by hand") }
            is ScanResult.Failed -> TextButton(onClick = onRetry) { Text("Retry") }
            else -> Unit
        }
    }
}
