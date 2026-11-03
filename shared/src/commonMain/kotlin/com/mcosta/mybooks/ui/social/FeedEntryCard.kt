package com.mcosta.mybooks.ui.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mcosta.mybooks.social.model.ActivityKind
import com.mcosta.mybooks.social.model.FeedEntry
import com.mcosta.mybooks.ui.components.BookCover

/**
 * One feed entry: who did what and when, the book, the review, and the heart.
 * [onLike] and [onReport] are null on the user's own entries (no heart button, no Report in the menu).
 */
@Composable
fun FeedEntryCard(
    entry: FeedEntry,
    onPerson: () -> Unit,
    onBook: () -> Unit,
    onLike: (() -> Unit)?,
    onLikers: () -> Unit,
    onReport: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var reviewExpanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clickable(onClick = onPerson)) { PersonAvatar(entry.actor.avatarUrl, size = 40.dp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(entry.actor.displayName) }
                        append(" ")
                        append(actionText(entry))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable(onClick = onPerson),
                )
                Text(relativeTime(entry.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onReport != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Report") }, onClick = { menuOpen = false; onReport() })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().clickable(onClick = onBook), verticalAlignment = Alignment.CenterVertically) {
            BookCover(entry.book.coverUrl, Modifier.width(48.dp).height(72.dp), title = entry.book.title, authors = entry.book.authors)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (entry.book.authors.isNotBlank()) {
                    Text(entry.book.authors, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (entry.kind == ActivityKind.PROGRESS && entry.progress != null) {
                    LinearProgressIndicator(progress = { entry.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
        }
        val review = entry.review
        if (!review.isNullOrBlank()) {
            Text(
                review,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (reviewExpanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { reviewExpanded = !reviewExpanded },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onLike != null) {
                IconButton(onClick = onLike, modifier = Modifier.size(40.dp)) {
                    if (entry.likedByMe) {
                        Icon(Icons.Filled.Favorite, "Unlike", tint = MaterialTheme.colorScheme.primary)
                    } else {
                        Icon(Icons.Filled.FavoriteBorder, "Like")
                    }
                }
            }
            if (entry.likeCount > 0) {
                TextButton(onClick = onLikers) { Text(if (entry.likeCount == 1) "1 like" else "${entry.likeCount} likes") }
            }
        }
    }
}
