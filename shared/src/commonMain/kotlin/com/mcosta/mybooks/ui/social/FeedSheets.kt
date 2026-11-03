package com.mcosta.mybooks.ui.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.social.model.FeedBook
import com.mcosta.mybooks.social.model.Person
import com.mcosta.mybooks.ui.components.BookCover

/** Who liked an entry; tapping a person opens their profile. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LikersSheet(people: List<Person>, onPerson: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Liked by", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            if (people.isEmpty()) item { EmptyText("Nobody yet") }
            items(people, key = { it.id }) { person ->
                PersonRow(person, onClick = { onPerson(person.id) }) { }
            }
        }
    }
}

/** A book from the feed, with "Add to my books" (or a note that it's already in the library). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedBookSheet(book: FeedBook, inLibrary: Boolean, onAdd: (ReadingStatus) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BookCover(book.coverUrl, Modifier.width(80.dp).height(120.dp), title = book.title, authors = book.authors)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleMedium)
                    if (book.authors.isNotBlank()) Text(book.authors, style = MaterialTheme.typography.bodyMedium)
                    book.isbn?.let { Text("ISBN $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (inLibrary) {
                Text("Already in your library", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            } else {
                Text("Add to my books", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onAdd(ReadingStatus.TO_READ) }) { Text("To read") }
                    OutlinedButton(onClick = { onAdd(ReadingStatus.WISHLIST) }) { Text("To buy") }
                }
            }
        }
    }
}
