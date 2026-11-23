package com.mcosta.mybooks.ui.physical

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.domain.searchKey
import com.mcosta.mybooks.physical.Bookcase
import com.mcosta.mybooks.physical.ShelfPlace
import com.mcosta.mybooks.physical.byRoom
import com.mcosta.mybooks.physical.findShelf

/** Search your books and tick the ones on this shelf. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickBooksSheet(
    books: List<Book>,
    bookcases: List<Bookcase>,
    ebookIds: Set<Long>,
    shelfId: String,
    onPick: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        PickBooksContent(books, bookcases, ebookIds, shelfId, onPick)
    }
}

/** Physical books not on this shelf. A book already on another one says where, and picking it moves it. */
@Composable
fun PickBooksContent(
    books: List<Book>,
    bookcases: List<Bookcase>,
    ebookIds: Set<Long>,
    shelfId: String,
    onPick: (List<Long>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(emptyList<Long>()) }
    val wanted = searchKey(query.trim())
    val shown = books
        .filter { it.status != ReadingStatus.WISHLIST && it.id !in ebookIds && it.shelfId != shelfId }
        .filter { wanted.isEmpty() || wanted in searchKey("${it.title}\n${it.authors}") }
        .sortedBy { it.title.lowercase() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("Search your books") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        if (shown.isEmpty()) Text("No books to add.", style = MaterialTheme.typography.bodyMedium)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
            items(shown, key = { it.id }) { book ->
                val checked = book.id in picked
                val where = bookcases.findShelf(book.shelfId)?.location
                ListItem(
                    headlineContent = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(listOfNotNull(book.authors.takeIf { it.isNotBlank() }, where).joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                    modifier = Modifier.clickable { picked = if (checked) picked - book.id else picked + book.id },
                )
            }
        }
        Button(enabled = picked.isNotEmpty(), onClick = { onPick(picked) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (picked.isEmpty()) "Put on this shelf" else "Put ${picked.size} on this shelf")
        }
    }
}

/** Every shelf, by room and bookcase. */
internal fun List<Bookcase>.allShelves(): List<ShelfPlace> =
    byRoom().flatMap { (_, cases) -> cases.flatMap { it.columns.flatMap { column -> column.shelves } } }
        .mapNotNull { findShelf(it) }

/** Choose a shelf: for one book or several. */
@Composable
internal fun ShelfPickerDialog(
    bookcases: List<Bookcase>,
    current: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a shelf") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(bookcases.allShelves().filter { it.shelfId != current }, key = { it.shelfId }) { place ->
                    Text(
                        place.location,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(place.shelfId) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
