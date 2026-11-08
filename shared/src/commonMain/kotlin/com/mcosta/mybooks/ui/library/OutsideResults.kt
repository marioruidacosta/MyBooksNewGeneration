package com.mcosta.mybooks.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcosta.mybooks.data.authors.AuthorBook
import com.mcosta.mybooks.data.releases.monthLabel
import com.mcosta.mybooks.ui.components.BookCover

/**
 * Below an empty library search: "No books match", the ISBN hint, and, once the search has gone outside, the
 * New in Portugal and Public sources sections, then "Not here? Add it yourself".
 */
@Composable
fun OutsideResults(
    state: OutsideSearchState,
    onRetry: (OutsideSection) -> Unit,
    onOpenRelease: (month: String, id: String) -> Unit,
    onOpenCatalogBook: (AuthorBook) -> Unit,
    onAddYourself: (isbn: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier) {
        item {
            Text(
                "No books match",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
            )
        }
        if (state.invalidIsbn) {
            item {
                Text(
                    "Not a valid ISBN — check the digits",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }
        }
        if (state.shown) {
            section(
                "New in Portugal", OutsideSection.RELEASES, state.releases,
                searching = if (state.monthsTotal > 0) "Searching… ${state.monthsLoaded} of ${state.monthsTotal} months" else "Searching…",
                empty = "Nothing in New in Portugal", failed = "Couldn't search New in Portugal",
                onRetry = { onRetry(OutsideSection.RELEASES) },
                key = { "release:${it.book.id}" },
            ) { hit ->
                ResultRow(hit.book.listCover, hit.book.title, hit.book.authors.joinToString(", "), monthLabel(hit.month, hit.complete)) {
                    onOpenRelease(hit.month, hit.book.id)
                }
            }
            section(
                "Public sources", OutsideSection.ONLINE, state.online, searching = "Searching…",
                empty = "Nothing found online", failed = "Couldn't search online",
                onRetry = { onRetry(OutsideSection.ONLINE) },
                key = null,
            ) { book ->
                ResultRow(book.coverUrl, book.title, book.authors, book.year?.toString()) { onOpenCatalogBook(book) }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Not here?", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { onAddYourself(state.isbn) }) { Text("Add it yourself") }
            }
        }
        item { Spacer(Modifier.height(88.dp)) }
    }
}

/** A section's header and its state; rows keyed by [key], or by section and position when null (no stable id). */
private fun <T> LazyListScope.section(
    title: String,
    id: OutsideSection,
    section: Section<T>,
    searching: String,
    empty: String,
    failed: String,
    onRetry: () -> Unit,
    key: ((T) -> Any)?,
    row: @Composable (T) -> Unit,
) {
    item(key = "header:$id", contentType = "header") {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    when (section) {
        Section.Idle -> Unit
        Section.Searching -> item(key = "searching:$id") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(searching, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
            }
        }
        Section.Empty -> item(key = "empty:$id") { Note(empty) }
        Section.Failed -> item(key = "failed:$id") {
            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(failed, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
        is Section.Results -> itemsIndexed(
            section.items,
            key = { i, item -> key?.invoke(item) ?: "$id:$i" },
            contentType = { _, _ -> "row" },
        ) { _, item ->
            row(item)
            HorizontalDivider()
        }
    }
}

@Composable
private fun Note(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
)

@Composable
private fun ResultRow(cover: String?, title: String, authors: String, detail: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(cover, Modifier.size(width = 40.dp, height = 60.dp), title = title, authors = authors)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (authors.isNotBlank()) {
                Text(authors, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
