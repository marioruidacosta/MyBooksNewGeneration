package com.mcosta.mybooks.ui.activity

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
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.ui.components.BookCover
import com.mcosta.mybooks.ui.formatDate

/** The books read in a year as a cover grid, or the read books without a date as a list to fix. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadYearScreen(viewModel: ReadYearViewModel, onBack: () -> Unit, onOpenBook: (Long) -> Unit) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val year = viewModel.year
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (year == null) "Read, no date" else "Read in $year")
                        books?.let { Text(if (it.size == 1) "1 book" else "${it.size} books", style = MaterialTheme.typography.bodySmall) }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        val list = books
        when {
            list == null -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No books here", style = MaterialTheme.typography.bodyLarge)
            }
            year == null -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                item {
                    Text(
                        "These count toward no year's challenge. Open a book and set its read date.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                items(list, key = { it.id }) { book ->
                    UndatedRow(book, onClick = { onOpenBook(book.id) })
                    HorizontalDivider()
                }
            }
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Spacer(Modifier.height(4.dp)) }
                items(list.chunked(4)) { row -> CoverRow(row, onOpenBook) }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun UndatedRow(book: Book, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BookCover(book.coverUrl, Modifier.width(40.dp).height(60.dp), title = book.title, authors = book.authors)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (book.authors.isNotBlank()) {
                Text(book.authors, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("Added ${formatDate(book.dateAdded)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
