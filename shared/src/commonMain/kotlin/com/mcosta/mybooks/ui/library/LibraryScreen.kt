package com.mcosta.mybooks.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.FilterChip
import com.mcosta.mybooks.domain.BookFilter
import com.mcosta.mybooks.ui.formatDate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.ui.components.BookCover
import com.mcosta.mybooks.ui.components.RatingStars
import kotlinx.coroutines.launch

fun ReadingStatus?.label() = when (this) {
    null -> "All"
    ReadingStatus.READING -> "Reading"
    ReadingStatus.TO_READ -> "To read"
    ReadingStatus.READ -> "Read"
    ReadingStatus.WISHLIST -> "To buy"
}

/** One shelf's books (the view model's section), with search and that shelf's own sort. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    title: String,
    /** Null when opened from the bottom bar: no back arrow. */
    onBack: (() -> Unit)?,
    onOpenBook: (Long) -> Unit,
    /** Opened from the home screen's search: the search box takes the keyboard. */
    focusSearch: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(state.query) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    var searchFocused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (focusSearch && !searchFocused) {
            searchFocused = true
            searchFocus.requestFocus()
        }
    }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    /** Swipes: right marks Read (or To read), left deletes; both offer Undo. */
    val onSwipeRight = { book: Book ->
        viewModel.swipeStatus(book)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val moved = if (swipeTarget(book) == ReadingStatus.READ) "Marked as Read" else "Moved to To read"
            if (snackbar.showSnackbar(moved, actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                viewModel.undoStatus(book)
            }
        }
        Unit
    }
    val onSwipeLeft = { book: Book ->
        viewModel.hide(book.id)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar("Deleted “${book.title}”", actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(book.id) else viewModel.delete(book.id)
        }
        Unit
    }
    // A new order or tab starts at the top. Otherwise the list keeps the book that was on top in view, and a
    // reversed order moves it to the end, so the list seemed not to change. Saved so coming back from a book
    // keeps the place.
    val shown = "${state.tab}/${state.sort.encode()}"
    var lastShown by rememberSaveable { mutableStateOf(shown) }
    LaunchedEffect(shown) {
        if (shown != lastShown) {
            lastShown = shown
            listState.scrollToItem(0)
            gridState.scrollToItem(0)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (state.filter is BookFilter.Tag) {
                        IconButton(onClick = {
                            viewModel.setPinned(!state.pinned)
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                snackbar.showSnackbar(if (state.pinned) "Removed from Home" else "Shown on Home")
                            }
                        }) {
                            if (state.pinned) Icon(Icons.Filled.PushPin, "Remove from Home") else Icon(Icons.Outlined.PushPin, "Show on Home")
                        }
                    }
                    IconButton(onClick = { viewModel.setGrid(!state.grid) }) {
                        if (state.grid) Icon(Icons.AutoMirrored.Filled.ViewList, "Show as list") else Icon(Icons.Filled.GridView, "Show as grid")
                    }
                    if (state.sortable) Box {
                        IconButton(onClick = { sortMenuOpen = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            for (order in state.sorts) {
                                DropdownMenuItem(
                                    text = { Text(order.label) },
                                    leadingIcon = { if (order == state.sort.order) Icon(Icons.Filled.Check, "Selected") },
                                    onClick = { sortMenuOpen = false; viewModel.setSort(order) },
                                )
                            }
                            HorizontalDivider()
                            for (descending in listOf(false, true)) {
                                val order = state.sort.order
                                DropdownMenuItem(
                                    text = { Text(if (descending) order.descending else order.ascending) },
                                    leadingIcon = { if (descending == state.sort.descending) Icon(Icons.Filled.Check, "Selected") },
                                    onClick = { sortMenuOpen = false; viewModel.setDescending(descending) },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; viewModel.setQuery(it) },
                placeholder = { Text("Search title or author") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(searchFocus),
            )
            if (state.tags.isNotEmpty()) TagChips(state.tags, state.tag, viewModel::chooseTag)
            if (state.books.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (query.isBlank()) "No books here yet" else "No books match", style = MaterialTheme.typography.bodyLarge)
                }
            } else if (state.grid) {
                LazyVerticalGrid(
                    GridCells.Fixed(3),
                    state = gridState,
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.books, key = { it.id }) { book -> GridCell(book, onClick = { onOpenBook(book.id) }) }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(state.books, key = { it.id }) { book ->
                        SwipeRow(book, onSwipeRight = { onSwipeRight(book) }, onSwipeLeft = { onSwipeLeft(book) }) {
                            BookRow(book, onClick = { onOpenBook(book.id) }, showSeriesNumber = state.filter is BookFilter.Series)
                        }
                        HorizontalDivider()
                    }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
    }
}

@Composable
private fun BookRow(book: Book, onClick: () -> Unit, showSeriesNumber: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book.coverUrl, Modifier.width(48.dp).height(72.dp), title = book.title, authors = book.authors)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (showSeriesNumber && book.seriesNumber != null) {
                Text("#${book.seriesNumber}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            book.lentTo?.let {
                Text(
                    lentText(it, book.lentOn),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (book.authors.isNotBlank()) {
                Text(
                    book.authors,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            book.publisher?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (book.rating != null) RatingStars(book.rating, onRate = null, size = 16.dp)
            if (book.status == ReadingStatus.READING && book.progressPercent != null) {
                LinearProgressIndicator(
                    progress = { book.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

/** "Lent to Ana since 4 Nov 2026". */
fun lentText(person: String, since: Long?): String = "Lent to $person" + (since?.let { " since ${formatDate(it)}" } ?: "")

/** A shelf's tags as chips; choosing one shows only its books, choosing it again shows them all. */
@Composable
private fun TagChips(tags: List<String>, chosen: String?, onChoose: (String?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(tags, key = { it.lowercase() }) { tag ->
            val selected = tag.equals(chosen, ignoreCase = true)
            FilterChip(
                selected = selected,
                onClick = { onChoose(if (selected) null else tag) },
                label = { Text(tag) },
                leadingIcon = if (selected) ({ Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }) else null,
            )
        }
    }
}

/** A list row that swipes: right for [onSwipeRight] (Read, or To read), left for [onSwipeLeft] (delete). */
@Composable
private fun SwipeRow(book: Book, onSwipeRight: () -> Unit, onSwipeLeft: () -> Unit, content: @Composable () -> Unit) {
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = swipe,
        backgroundContent = {
            val right = swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val left = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
            val color = when {
                right -> MaterialTheme.colorScheme.primaryContainer
                left -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surface
            }
            Row(
                Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (left) Arrangement.End else Arrangement.Start,
            ) {
                when {
                    right -> {
                        val toRead = swipeTarget(book) == ReadingStatus.TO_READ
                        Icon(if (toRead) Icons.Filled.Bookmark else Icons.Filled.Done, null)
                        Text(if (toRead) "  To read" else "  Read", style = MaterialTheme.typography.labelLarge)
                    }
                    left -> {
                        Text("Delete  ", style = MaterialTheme.typography.labelLarge)
                        Icon(Icons.Filled.Delete, null)
                    }
                }
            }
        },
        onDismiss = { direction ->
            when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> onSwipeRight()
                SwipeToDismissBoxValue.EndToStart -> onSwipeLeft()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            // The row slides back: a status change keeps it (or moves it to another shelf); a deletion hides it.
            scope.launch { swipe.reset() }
        },
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.surface)) { content() }
    }
}

/** A cover in the grid, with its title below. */
@Composable
private fun GridCell(book: Book, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(shape = RoundedCornerShape(4.dp), shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            BookCover(book.coverUrl, Modifier.fillMaxSize(), title = book.title, authors = book.authors)
        }
        Text(book.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (book.rating != null) RatingStars(book.rating, onRate = null, size = 12.dp)
    }
}
