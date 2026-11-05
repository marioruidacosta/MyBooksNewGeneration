package com.mcosta.mybooks.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.MAX_TAG_LENGTH
import com.mcosta.mybooks.domain.hasTag
import com.mcosta.mybooks.ui.formatDate
import com.mcosta.mybooks.ui.library.label
import com.mcosta.mybooks.ui.library.lentText
import kotlin.time.Clock

/** The book's tags: tap one for all its books, × to take it off, "Add tag" for a new or existing one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagsSection(book: Book, allTags: List<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit, onOpen: (String) -> Unit) {
    var adding by rememberSaveable { mutableStateOf(false) }
    SectionCard("Tags") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (tag in book.tags) {
                InputChip(
                    selected = false,
                    onClick = { onOpen(tag) },
                    label = { Text(tag) },
                    trailingIcon = {
                        Icon(Icons.Filled.Close, "Remove $tag", Modifier.size(18.dp).clickable { onRemove(tag) })
                    },
                )
            }
            AssistChip(
                onClick = { adding = true },
                label = { Text("Add tag") },
                leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)) },
            )
        }
    }
    if (adding) {
        AddTagDialog(
            offered = allTags.filterNot { book.hasTag(it) },
            onAdd = { onAdd(it); adding = false },
            onDismiss = { adding = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddTagDialog(offered: List<String>, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val matching = offered.filter { it.contains(text.trim(), ignoreCase = true) }.take(12)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add tag") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.replace("\n", "").take(MAX_TAG_LENGTH) },
                    placeholder = { Text("e.g. classics, gift from Ana") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onAdd(text) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (matching.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (tag in matching) SuggestionChip(onClick = { onAdd(tag) }, label = { Text(tag) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The series in order, as far as the library has it; this book in bold, the others open on tap. */
@Composable
internal fun SeriesSection(book: Book, books: List<Book>, onOpenBook: (Long) -> Unit, onOpenSeries: (String) -> Unit) {
    val series = book.series ?: return
    SectionCard("Series") {
        Row(
            Modifier.fillMaxWidth().clickable { onOpenSeries(series) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (book.seriesNumber != null) "Book ${book.seriesNumber} of $series" else series,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "All of $series")
        }
        if (books.size > 1) {
            for (b in books) {
                val current = b.id == book.id
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !current) { onOpenBook(b.id) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        b.seriesNumber?.let { "#$it" } ?: "–",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(48.dp),
                    )
                    Text(
                        b.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (current) FontWeight.Bold else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        b.status.label(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Who has the book: "Lent to Ana since …" with Returned, or a Lend button when it is at home. */
@Composable
internal fun LoanSection(book: Book, onLend: (person: String, date: Long) -> Unit, onReturned: () -> Unit) {
    var lending by rememberSaveable { mutableStateOf(false) }
    val person = book.lentTo
    if (person != null) {
        SectionCard("Lent out") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(lentText(person, book.lentOn), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onReturned) { Text("Returned") }
            }
        }
    } else {
        TextButton(onClick = { lending = true }) {
            Icon(Icons.Outlined.Handshake, null, Modifier.size(18.dp))
            Text("Lend this book", Modifier.padding(start = 8.dp))
        }
    }
    if (lending) LendDialog(onLend = { p, d -> onLend(p, d); lending = false }, onDismiss = { lending = false })
}

@Composable
private fun LendDialog(onLend: (String, Long) -> Unit, onDismiss: () -> Unit) {
    var person by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lend this book") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = person,
                    onValueChange = { person = it.replace("\n", "") },
                    label = { Text("Lent to") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("On ${formatDate(date)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { picking = true }) { Text("Change") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onLend(person, date) }, enabled = person.isNotBlank()) { Text("Lend") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (picking) DayPicker(initial = date, onPicked = { date = it; picking = false }, onDismiss = { picking = false })
}
