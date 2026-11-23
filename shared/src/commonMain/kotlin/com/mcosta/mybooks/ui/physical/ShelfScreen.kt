package com.mcosta.mybooks.ui.physical

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.physical.findShelf
import com.mcosta.mybooks.physical.onShelf
import com.mcosta.mybooks.ui.library.lentText
import kotlin.math.roundToInt

/** A shelf's books, left to right as a list: open one, take it off, change the order, or add more. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen(
    viewModel: PhysicalLibraryViewModel,
    shelfId: String,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onScan: (() -> Unit)?,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var picking by remember { mutableStateOf(false) }
    PhysicalMessages(state, viewModel, snackbar)
    val place = viewModel.place(shelfId)
    LaunchedEffect(place == null) { if (place == null) onBack() }
    if (place == null) return
    val books = state.books.onShelf(shelfId)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Shelf ${place.label}")
                        Text(
                            "${place.bookcase.room} › ${place.bookcase.name}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onScan != null) Button(onClick = onScan, modifier = Modifier.weight(1f)) { Text("Scan") }
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.weight(1f)) { Text("Pick from library") }
            }
            if (books.isEmpty()) {
                Text("No books on this shelf yet.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(books, key = { _, book -> book.id }) { index, book ->
                    ShelfBookRow(
                        book, index, books.size,
                        onOpen = { onOpenBook(book.id) },
                        onTakeOff = { viewModel.takeOff(book.id) },
                        onMove = { viewModel.moveBook(book.id, shelfId, it) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    if (picking) {
        PickBooksSheet(
            state.books, state.bookcases, state.ebookIds, shelfId,
            onPick = { picking = false; viewModel.place(it, shelfId) },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun ShelfBookRow(
    book: Book,
    index: Int,
    count: Int,
    onOpen: () -> Unit,
    onTakeOff: () -> Unit,
    onMove: (Int) -> Unit,
) {
    var dragged by remember(book.id) { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(1) }
    val detail = listOfNotNull(book.authors.takeIf { it.isNotBlank() }, book.lentTo?.let { lentText(it, book.lentOn) }).joinToString(" · ")
    ListItem(
        headlineContent = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Icon(
                Icons.Filled.DragHandle, null,
                Modifier.size(32.dp).pointerInput(book.id, index, count) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            val target = (index + (dragged / rowHeight).roundToInt()).coerceIn(0, count - 1)
                            dragged = 0f
                            if (target != index) onMove(target)
                        },
                        onDragCancel = { dragged = 0f },
                    ) { change, amount ->
                        change.consume()
                        dragged += amount
                    }
                },
            )
        },
        trailingContent = {
            IconButton(onClick = onTakeOff) { Icon(Icons.Filled.Close, "Take ${book.title} off this shelf") }
        },
        modifier = Modifier
            .onSizeChanged { rowHeight = it.height.coerceAtLeast(1) }
            .zIndex(if (dragged != 0f) 1f else 0f)
            .graphicsLayer { translationY = dragged }
            .clickable(onClick = onOpen)
            .semantics {
                customActions = buildList {
                    if (index > 0) add(CustomAccessibilityAction("Move up") { onMove(index - 1); true })
                    if (index < count - 1) add(CustomAccessibilityAction("Move down") { onMove(index + 1); true })
                }
            },
    )
}
