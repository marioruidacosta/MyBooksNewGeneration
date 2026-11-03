package com.mcosta.mybooks.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.ui.components.BookCover
import com.mcosta.mybooks.ui.components.RatingStars

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
    // A new order or tab starts at the top. Otherwise the list keeps the book that was on top in view, and a
    // reversed order moves it to the end, so the list seemed not to change. Saved so coming back from a book
    // keeps the place.
    val shown = "${state.tab}/${state.sort.encode()}"
    var lastShown by rememberSaveable { mutableStateOf(shown) }
    LaunchedEffect(shown) {
        if (shown != lastShown) {
            lastShown = shown
            listState.scrollToItem(0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    Box {
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
            if (state.books.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (query.isBlank()) "No books here yet" else "No books match", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(state.books, key = { it.id }) { book ->
                        BookRow(book, onClick = { onOpenBook(book.id) })
                        HorizontalDivider()
                    }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
    }
}

@Composable
private fun BookRow(book: Book, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book.coverUrl, Modifier.width(48.dp).height(72.dp), title = book.title, authors = book.authors)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
