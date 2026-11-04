package com.mcosta.mybooks.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.ui.library.label

/**
 * The cover's third panel: the author's photo and short biography (from Wikipedia, credited) and the library's
 * other books by them. Plain text over the blurred backdrop, scrolling within the cover's height.
 */
@Composable
fun AuthorPanelView(panel: AuthorPanel?, onOpenBook: (Long) -> Unit) {
    val uris = LocalUriHandler.current
    Box(Modifier.fillMaxHeight().widthIn(max = 320.dp).fillMaxWidth()) {
        when {
            panel == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            panel.name.isEmpty() -> Text(
                "No author recorded for this book.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    panel.info?.photoUrl?.let { photo ->
                        AsyncImage(
                            model = photo,
                            contentDescription = panel.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(CircleShape),
                        )
                    }
                    Column(Modifier.padding(start = if (panel.info?.photoUrl != null) 12.dp else 0.dp)) {
                        Text(panel.info?.name ?: panel.name, style = MaterialTheme.typography.titleMedium)
                        panel.info?.description?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                val info = panel.info
                when {
                    info != null -> {
                        Text(info.extract, style = MaterialTheme.typography.bodySmall)
                        info.pageUrl?.let { url ->
                            Text(
                                "Wikipedia",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { runCatching { uris.openUri(url) } },
                            )
                        }
                    }
                    panel.loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                Text(
                    if (panel.otherBooks.isEmpty()) "No other books by them in your library"
                    else "In your library · ${panel.otherBooks.size} other ${if (panel.otherBooks.size == 1) "book" else "books"}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
                for (book in panel.otherBooks) {
                    Row(Modifier.fillMaxWidth().clickable { onOpenBook(book.id) }.padding(vertical = 2.dp)) {
                        Text(book.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(
                            if (book.status == ReadingStatus.READ && book.rating != null) "★".repeat(book.rating) else book.status.label(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
