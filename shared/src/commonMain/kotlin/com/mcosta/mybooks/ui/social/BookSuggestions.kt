package com.mcosta.mybooks.ui.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.share.publicState
import com.mcosta.mybooks.social.SocialServices
import com.mcosta.mybooks.social.model.BookSuggestion
import com.mcosta.mybooks.social.model.FeedBook
import com.mcosta.mybooks.social.model.Person
import com.mcosta.mybooks.social.model.SUGGESTION_MAX_FRIENDS
import com.mcosta.mybooks.social.model.SUGGESTION_NOTE_MAX
import com.mcosta.mybooks.social.socialMessage
import com.mcosta.mybooks.ui.components.BookCover
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What a friend gets of a book from the library: only what the community may see (no private cover photo). */
fun Book.toSuggestedBook(): FeedBook = publicState("").let {
    FeedBook(it.shareId, it.isbn, it.title, it.authors, it.coverUrl, it.blurb, it.pages)
}

/** What sending said: [sent] of the friends [asked] got it; the others already had this book from you. */
fun suggestionSentText(sent: Int, asked: List<Person>): String = when {
    sent == 0 -> "You've already suggested this to ${asked.singleOrNull()?.displayName ?: "them"}"
    sent < asked.size -> "Suggested to $sent of ${asked.size} friends"
    asked.size == 1 -> "Suggested to ${asked[0].displayName}"
    else -> "Suggested to ${asked.size} friends"
}

/** [text] within the note's limit, never cut in the middle of an emoji. */
fun fitNote(text: String): String {
    if (text.length <= SUGGESTION_NOTE_MAX) return text
    val cut = text.take(SUGGESTION_NOTE_MAX)
    return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

/** "Suggested to you": at the top of Community, one card per suggestion. */
@Composable
fun SuggestedToYou(
    items: List<BookSuggestion>,
    onPerson: (String) -> Unit,
    onBook: (FeedBook) -> Unit,
    onAccept: (BookSuggestion, ReadingStatus) -> Unit,
    onDismiss: (BookSuggestion) -> Unit,
    onReport: (BookSuggestion) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            "Suggested to you · ${items.size}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        items.forEachIndexed { index, suggestion ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SuggestionCard(suggestion, onPerson, onBook, onAccept, onDismiss, onReport)
        }
    }
    HorizontalDivider()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionCard(
    suggestion: BookSuggestion,
    onPerson: (String) -> Unit,
    onBook: (FeedBook) -> Unit,
    onAccept: (BookSuggestion, ReadingStatus) -> Unit,
    onDismiss: (BookSuggestion) -> Unit,
    onReport: (BookSuggestion) -> Unit,
) {
    val book = suggestion.book
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BookCover(
            book.coverUrl,
            Modifier.width(64.dp).height(96.dp).clickable(onClickLabel = "Open ${book.title}") { onBook(book) },
            title = book.title,
            authors = book.authors,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${suggestion.sender.displayName} suggested",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable { onPerson(suggestion.sender.id) },
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More about this suggestion") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Report") }, onClick = { menuOpen = false; onReport(suggestion) })
                    }
                }
            }
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (book.authors.isNotBlank()) {
                Text(
                    book.authors,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (suggestion.note.isNotBlank()) {
                Text("“${suggestion.note}”", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
            }
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAccept(suggestion, ReadingStatus.TO_READ) }) { Text("Want to read") }
                OutlinedButton(onClick = { onAccept(suggestion, ReadingStatus.WISHLIST) }) { Text("To buy") }
                TextButton(onClick = { onDismiss(suggestion) }) { Text("Dismiss") }
            }
        }
    }
}

/** "Suggest to a friend" as a bottom sheet; [onDone] gets what to tell the person. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestBookSheet(book: FeedBook, services: SocialServices, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SuggestBookContent(
            book,
            loadFriends = services.friends::friends,
            send = { friends, note -> services.activity.suggestBook(book, friends, note) },
            onDone = onDone,
        )
    }
}

/** Choose friends, add a note if you like, and send. */
@Composable
fun SuggestBookContent(
    book: FeedBook,
    loadFriends: suspend () -> List<Person>,
    send: suspend (friends: List<String>, note: String) -> Int,
    onDone: (String) -> Unit,
) {
    var friends by remember { mutableStateOf<List<Person>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    var note by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(attempt) {
        loadError = null
        try {
            friends = loadFriends().sortedBy { it.displayName.lowercase() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = socialMessage(e)
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Suggest to a friend", style = MaterialTheme.typography.titleMedium)
        Text(book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val people = friends
        when {
            loadError != null -> {
                Text(loadError!!, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { attempt++ }) { Text("Retry") }
            }
            people == null -> Text("Loading friends…", style = MaterialTheme.typography.bodyMedium)
            people.isEmpty() -> Text("Add friends in Community to suggest books to them.", style = MaterialTheme.typography.bodyMedium)
            else -> {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    items(people, key = { it.id }) { person ->
                        val checked = person.id in chosen
                        PersonRow(person, onClick = {
                            chosen = when {
                                checked -> chosen - person.id
                                chosen.size < SUGGESTION_MAX_FRIENDS -> chosen + person.id
                                else -> chosen
                            }
                        }) {
                            Checkbox(checked = checked, onCheckedChange = null)
                        }
                    }
                }
                if (chosen.size >= SUGGESTION_MAX_FRIENDS) {
                    Text("Up to $SUGGESTION_MAX_FRIENDS friends at a time", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = fitNote(it) },
                    label = { Text("Note (optional)") },
                    supportingText = { Text("${note.length}/$SUGGESTION_NOTE_MAX") },
                    modifier = Modifier.fillMaxWidth(),
                )
                sendError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    enabled = chosen.isNotEmpty() && !sending,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val asked = people.filter { it.id in chosen }
                        sending = true
                        sendError = null
                        scope.launch {
                            try {
                                val sent = send(asked.map { it.id }, note.trim())
                                onDone(suggestionSentText(sent, asked))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                sendError = socialMessage(e)
                            } finally {
                                sending = false
                            }
                        }
                    },
                ) { Text(if (sending) "Sending…" else "Send") }
            }
        }
    }
}
