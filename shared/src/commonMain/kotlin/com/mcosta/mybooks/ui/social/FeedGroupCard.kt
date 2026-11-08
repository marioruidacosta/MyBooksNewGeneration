package com.mcosta.mybooks.ui.social

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mcosta.mybooks.social.model.FeedBook
import com.mcosta.mybooks.ui.components.BookCover

/** Covers shown before "+N" on a grouped card. */
private const val GROUP_COVERS = 4

/**
 * Several books one person added at once: "Marta added 5 books to To read", a row of covers (tap one for its book)
 * and a "+N" tile that shows them all. No heart: likes stay on single updates.
 */
@Composable
fun FeedGroupCard(group: FeedItem.Group, onPerson: () -> Unit, onBook: (FeedBook) -> Unit, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(group.key) { mutableStateOf(false) }
    val books = group.entries.map { it.book }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PersonLine(group.actor, groupText(group), relativeTime(group.entries.first().createdAt), onPerson)
        val shown = if (expanded || books.size <= GROUP_COVERS) books else books.take(GROUP_COVERS)
        val rows = shown.chunked(GROUP_COVERS + 1)
        rows.forEachIndexed { index, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { book -> GroupCover(book, Modifier.clickable { onBook(book) }) }
                if (index == rows.lastIndex && books.size > shown.size) {
                    Box(
                        Modifier.width(56.dp).height(84.dp).clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { expanded = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("+${books.size - shown.size}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupCover(book: FeedBook, modifier: Modifier = Modifier) {
    BookCover(book.coverUrl, modifier.width(56.dp).height(84.dp), title = book.title, authors = book.authors)
}
