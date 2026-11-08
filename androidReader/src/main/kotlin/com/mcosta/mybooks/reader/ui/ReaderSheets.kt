package com.mcosta.mybooks.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcosta.mybooks.reader.LookFont
import com.mcosta.mybooks.reader.LookTheme
import com.mcosta.mybooks.reader.ReadingLook
import com.mcosta.mybooks.reader.TocEntry
import com.mcosta.mybooks.reader.TocRow
import kotlin.math.roundToInt

/** The table of contents; choosing an entry goes there. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentsSheet(rows: List<TocRow>, onGo: (TocEntry) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Contents", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            items(rows) { row ->
                Text(
                    row.entry.title,
                    style = if (row.depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { onGo(row.entry) }
                        .padding(start = (24 + 16 * row.depth).dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}

/** Aa: text size, font, line spacing and colours, for every book. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LookSheet(look: ReadingLook, onChange: (ReadingLook) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Text size", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                OutlinedIconButton(onClick = { onChange(look.smaller()) }) { Icon(Icons.Filled.Remove, "Smaller") }
                Text("${(look.fontSize * 100).roundToInt()}%", style = MaterialTheme.typography.bodyLarge)
                OutlinedIconButton(onClick = { onChange(look.bigger()) }) { Icon(Icons.Filled.Add, "Bigger") }
            }
            Choices("Font", LookFont.entries, look.font, { it.label }) { onChange(look.copy(font = it)) }
            Choices("Line spacing", LINE_HEIGHTS, look.lineHeight, { if (it == null) "Book's" else "$it" }) { onChange(look.copy(lineHeight = it)) }
            Choices("Colours", LookTheme.entries, look.theme, { it.label }) { onChange(look.copy(theme = it)) }
        }
    }
}

private val LINE_HEIGHTS = listOf(null, 1.2, 1.5, 1.8)

private val LookFont.label get() = when (this) {
    LookFont.BOOK -> "Book's"
    LookFont.SERIF -> "Serif"
    LookFont.SANS -> "Sans serif"
}

private val LookTheme.label get() = when (this) {
    LookTheme.LIGHT -> "Light"
    LookTheme.SEPIA -> "Sepia"
    LookTheme.DARK -> "Dark"
}

@Composable
private fun <T> Choices(title: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in options) FilterChip(selected = option == selected, onClick = { onPick(option) }, label = { Text(label(option)) })
        }
    }
}

/** Bookmarks: keep the current page, go to one, or delete it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
    bookmarks: List<com.mcosta.mybooks.domain.Bookmark>,
    onAdd: () -> Unit,
    onOpen: (com.mcosta.mybooks.domain.Bookmark) -> Unit,
    onDelete: (com.mcosta.mybooks.domain.Bookmark) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Bookmarks", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            androidx.compose.material3.FilledTonalButton(onClick = onAdd) { Text("Bookmark this page") }
        }
        if (bookmarks.isEmpty()) {
            Text(
                "No bookmarks yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            items(bookmarks, key = { it.id }) { mark ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(mark) }.padding(start = 24.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(mark.label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                    androidx.compose.material3.IconButton(onClick = { onDelete(mark) }) {
                        Icon(Icons.Filled.Close, "Delete bookmark ${mark.label}")
                    }
                }
            }
        }
    }
}
