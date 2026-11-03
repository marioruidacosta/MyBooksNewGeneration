package com.mcosta.mybooks.ui.releases

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.data.releases.ReleaseBook
import com.mcosta.mybooks.data.releases.monthLabel
import com.mcosta.mybooks.ui.currentMonth
import com.mcosta.mybooks.ui.components.BookCover

/** One month's books, or with a null [month] every month's, under month headings; [onOpenBook] gets (month, book id). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseGenreScreen(
    viewModel: ReleaseGenreViewModel,
    title: String,
    month: String?,
    onBack: () -> Unit,
    onOpenBook: (month: String, id: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Completeness from the saved month list; the clock only until that is known.
    val subtitle = if (month == null) "All months" else monthLabel(month, state.monthComplete ?: (month < currentMonth()))
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.books.isEmpty() && state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.books.isEmpty() && state.error != null -> Message(state.error!!, onRetry = viewModel::retry)
                else -> Column(Modifier.fillMaxSize()) {
                    SavedNote(state.savedAt)
                    if (state.missing.isNotEmpty()) MissingNote(state.missing, onRetry = viewModel::retry)
                    LazyColumn(Modifier.fillMaxSize()) {
                        for (group in state.groups) {
                            if (month == null) {
                                item(key = group.month) {
                                    Text(
                                        monthLabel(group.month, group.complete),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                                    )
                                }
                            }
                            items(group.books, key = { "${group.month}/${it.id}" }) { book ->
                                BookRow(book, onClick = { onOpenBook(group.month, book.id) })
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookRow(book: ReleaseBook, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        BookCover(book.listCover, Modifier.width(48.dp).height(72.dp), title = book.title, authors = book.authors.joinToString(", "))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (book.authors.isNotEmpty()) {
                Text(book.authors.joinToString(", "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            book.publisher?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}
