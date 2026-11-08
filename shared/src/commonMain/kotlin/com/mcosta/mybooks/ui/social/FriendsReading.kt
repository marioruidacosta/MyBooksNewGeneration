package com.mcosta.mybooks.ui.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcosta.mybooks.social.model.FeedBook
import com.mcosta.mybooks.social.model.FriendReading
import com.mcosta.mybooks.ui.components.BookCover

/** "Ana · 53%": the first name (the row is narrow), and the progress when known. */
fun readingLabel(reading: FriendReading): String =
    reading.person.displayName.trim().substringBefore(' ') + (reading.progress?.let { " · $it%" } ?: "")

/** "Friends are reading": a row of friends' current books, each with the friend's face and how far they are. */
@Composable
fun FriendsReadingRow(reading: List<FriendReading>, onBook: (FeedBook) -> Unit, onSeeAll: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Friends are reading", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onSeeAll) { Text("See all") }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(reading, key = { "${it.person.id}:${it.book.shareId}" }) { r ->
                Column(Modifier.width(72.dp).clickable { onBook(r.book) }) {
                    Box {
                        BookCover(r.book.coverUrl, Modifier.width(72.dp).height(108.dp), title = r.book.title, authors = r.book.authors)
                        PersonAvatar(r.person.avatarUrl, Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 8.dp), size = 26.dp)
                    }
                    Spacer(Modifier.height(12.dp))
                    r.progress?.let { LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth()) }
                    Text(
                        readingLabel(r),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** "See all": every friend's current book, with the friend, the book and the progress. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsReadingSheet(reading: List<FriendReading>, onBook: (FeedBook) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Friends are reading", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            items(reading, key = { "${it.person.id}:${it.book.shareId}" }) { r ->
                Row(
                    Modifier.fillMaxWidth().clickable { onBook(r.book) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PersonAvatar(r.person.avatarUrl, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    BookCover(r.book.coverUrl, Modifier.width(30.dp).height(45.dp), title = r.book.title, authors = r.book.authors)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${r.person.displayName} · ${r.book.title}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        r.progress?.let { LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) }
                    }
                    r.progress?.let { Text("$it%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp)) }
                }
            }
        }
    }
}
