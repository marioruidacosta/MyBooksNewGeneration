package com.mcosta.mybooks.ui.ebooks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcosta.mybooks.data.ebooks.EbookState
import com.mcosta.mybooks.ui.detail.SectionCard
import com.mcosta.mybooks.ui.formatDate

/** "1.2 MB", "640 KB". */
fun fileSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "${(bytes / 100_000) / 10.0} MB"
    else -> "${maxOf(1, bytes / 1000)} KB"
}

/** The big Read button on a book page, for a book whose ebook file is on this phone. */
@Composable
fun ReadButton(onRead: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onRead, modifier = modifier.fillMaxWidth()) {
        Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(18.dp))
        Text("Read", Modifier.padding(start = 8.dp))
    }
}

/**
 * The book page's Ebook card: the file's format, size and date with **Remove file**; "Ebook file not on this phone"
 * with **Add it again** after a restore; or **Add ebook file** for a book without one. With [elsewhere] (the library
 * syncs), a file that isn't here shows as "Ebook on another device": its record came with the library, and adding the
 * file here links it to that record (place and bookmarks included).
 */
@Composable
fun EbookCard(state: EbookState?, onAdd: () -> Unit, onRemove: () -> Unit, elsewhere: Boolean = false) {
    var confirmRemove by remember { mutableStateOf(false) }
    SectionCard("Ebook") {
        when {
            state == null -> TextButton(onClick = onAdd) { Text("Add ebook file") }
            !state.fileOnPhone && elsewhere -> Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ebook on another device", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${state.ebook.format.name} · ${state.ebook.fileName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onAdd) { Text("Add it here") }
            }
            !state.fileOnPhone -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ebook file not on this phone", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onAdd) { Text("Add it again") }
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${state.ebook.format.name} · ${fileSize(state.ebook.size)} · added ${formatDate(state.ebook.dateAdded)}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { confirmRemove = true }) { Text("Remove file") }
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove the ebook file?") },
            text = { Text("The file is deleted from this phone. Your place, bookmarks and highlights stay, for if you add it again.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; onRemove() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}
