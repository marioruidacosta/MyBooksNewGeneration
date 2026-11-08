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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.social.model.ActivityKind
import com.mcosta.mybooks.social.model.FeedBook
import com.mcosta.mybooks.social.model.FeedEntry
import com.mcosta.mybooks.social.model.Person
import com.mcosta.mybooks.ui.components.BookCover
import com.mcosta.mybooks.ui.library.label

/** The stars' colour (gold), readable on light and dark backgrounds. */
internal val StarColor = Color(0xFFF4B400)

/**
 * One feed entry: who did what and when, the stars and review, then the book in its own panel (cover, pages,
 * progress, blurb and one button), and the heart.
 * [onLike] and [onReport] are null on the user's own entries (no heart button, no Report in the menu).
 * [onWantToRead] null hides the button (the user's own entries, or screens that don't add books); when the book is
 * already in the library ([shelf]), the button opens the user's copy instead ([onOpenMine]).
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
    shelf: ReadingStatus? = null,
    onWantToRead: (() -> Unit)? = null,
    onWantToBuy: (() -> Unit)? = null,
    onOpenMine: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    var reviewExpanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PersonLine(entry.actor, actionText(entry), relativeTime(entry.createdAt), onPerson, Modifier.weight(1f))
            if (onReport != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Report") }, onClick = { menuOpen = false; onReport() })
                    }
                }
            }
        }
        if (entry.kind == ActivityKind.RATED || entry.kind == ActivityKind.REVIEWED) {
            starsText(entry.rating)?.let { Text(it, color = StarColor, style = MaterialTheme.typography.titleMedium) }
        }
        val review = entry.review
        if (!review.isNullOrBlank()) {
            Text(
                "“$review”",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = if (reviewExpanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { reviewExpanded = !reviewExpanded },
            )
        }
        BookPanel(
            book = entry.book,
            progress = entry.progress.takeIf { entry.kind == ActivityKind.PROGRESS },
            shelf = shelf,
            onBook = onBook,
            onWantToRead = onWantToRead,
            onWantToBuy = onWantToBuy,
            onOpenMine = onOpenMine,
        )
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

/** Avatar, "Name did something" and when: the first line of a card. */
@Composable
internal fun PersonLine(person: Person, action: String, time: String, onPerson: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clickable(onClick = onPerson), verticalAlignment = Alignment.CenterVertically) {
        PersonAvatar(person.avatarUrl, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(person.displayName) }
                    append(" ")
                    append(action)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The book in its own panel: cover, title, "author · N pages", progress, blurb and the add / in-library button. */
@Composable
private fun BookPanel(
    book: FeedBook,
    progress: Int?,
    shelf: ReadingStatus?,
    onBook: () -> Unit,
    onWantToRead: (() -> Unit)?,
    onWantToBuy: (() -> Unit)?,
    onOpenMine: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickable(onClick = onBook).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                BookCover(book.coverUrl, Modifier.width(64.dp).height(96.dp), title = book.title, authors = book.authors)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val byline = listOfNotNull(book.authors.takeIf { it.isNotBlank() }, book.pages?.let { "$it pages" }).joinToString(" · ")
                    if (byline.isNotEmpty()) {
                        Text(byline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        Text("$progress%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (onWantToBuy != null && shelf == null) {
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("To buy") }, onClick = { menuOpen = false; onWantToBuy() })
                        }
                    }
                }
            }
            book.blurb?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            when {
                onWantToRead == null -> Unit
                shelf != null -> OutlinedButton(onClick = onOpenMine, modifier = Modifier.fillMaxWidth()) { Text("✓ In your library · ${shelf.label()}") }
                else -> Button(onClick = onWantToRead, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Want to read")
                }
            }
        }
    }
}
