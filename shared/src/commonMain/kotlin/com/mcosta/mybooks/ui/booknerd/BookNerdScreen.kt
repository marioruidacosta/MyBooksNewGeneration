package com.mcosta.mybooks.ui.booknerd

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.data.suggestions.Suggestion
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.social.model.SuggestedReader
import com.mcosta.mybooks.ui.LocalPlatformServices
import com.mcosta.mybooks.ui.components.BookCover
import com.mcosta.mybooks.ui.library.label
import com.mcosta.mybooks.ui.social.PersonAvatar
import kotlinx.coroutines.launch

/** What the BookNerd chat can do; the defaults do nothing (screenshots). [onShare] null hides Share. */
data class BookNerdActions(
    val onSend: (String) -> Unit = {},
    val onChip: (String) -> Unit = {},
    val onAddBook: (Suggestion, ReadingStatus) -> Unit = { _, _ -> },
    val onAddFriend: (SuggestedReader) -> Unit = {},
    val onProfile: (String) -> Unit = {},
    val onJoin: () -> Unit = {},
    val onShare: ((String) -> Unit)? = null,
)

/** BookNerd, opened from Home: the chat with a back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookNerdScreen(viewModel: BookNerdViewModel, onBack: () -> Unit, onOpenProfile: (String) -> Unit, onJoin: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val shareText = LocalPlatformServices.current.shareText
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BookNerd") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().imePadding()) {
            BookNerdChat(
                state,
                BookNerdActions(
                    onSend = viewModel::send,
                    onChip = viewModel::chip,
                    onAddBook = viewModel::addBook,
                    onAddFriend = viewModel::addFriend,
                    onProfile = onOpenProfile,
                    onJoin = onJoin,
                    onShare = shareText?.let { share -> { text: String -> scope.launch { share(text) } } },
                ),
            )
        }
    }
}

/** The conversation, the chips and the text field. */
@Composable
fun BookNerdChat(state: BookNerdState, actions: BookNerdActions, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(state.messages.size) { if (state.messages.isNotEmpty()) list.animateScrollToItem(state.messages.lastIndex) }
    Column(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.messages, key = { it.id }) { message ->
                when (message) {
                    is BotMessage.BotText -> BotBubble(message.text)
                    is BotMessage.UserText -> UserBubble(message.text)
                    is BotMessage.BookCards -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        message.books.forEach { BookCard(it, state.added[it.key], actions.onAddBook) }
                    }
                    is BotMessage.PersonCards -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        message.readers.forEach { PersonCard(it, message.language, it.person.id in state.requested, actions) }
                    }
                    is BotMessage.AbstractCard -> AbstractCard(message.text, actions.onShare)
                    is BotMessage.JoinPrompt -> BotBubble("Join the community to get friend suggestions") {
                        Button(onClick = actions.onJoin, modifier = Modifier.padding(top = 8.dp)) { Text("Join") }
                    }
                }
            }
        }
        if (state.chips.isNotEmpty()) Chips(state.chips, enabled = !state.busy, onChip = actions.onChip)
        Input(enabled = !state.busy, onSend = actions.onSend)
    }
}

@Composable
private fun BotBubble(text: String, extra: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(32.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.SmartToy, contentDescription = "BookNerd", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Surface(shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).widthIn(max = 300.dp)) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
                extra()
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).widthIn(max = 280.dp))
        }
    }
}

@Composable
private fun BookCard(book: Suggestion, added: ReadingStatus?, onAdd: (Suggestion, ReadingStatus) -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(start = 40.dp)) {
        Row(Modifier.padding(12.dp)) {
            BookCover(book.coverUrl, Modifier.width(52.dp).height(78.dp), title = book.title, authors = book.authors)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (book.authors.isNotBlank()) Text(book.authors, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(book.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (added != null) {
                    Text("✓ In your library · ${added.label()}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Button(onClick = { onAdd(book, ReadingStatus.TO_READ) }) { Text("To read") }
                        OutlinedButton(onClick = { onAdd(book, ReadingStatus.WISHLIST) }) { Text("To buy") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonCard(reader: SuggestedReader, language: BotLanguage, requested: Boolean, actions: BookNerdActions) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(start = 40.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PersonAvatar(reader.person.avatarUrl, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(reader.person.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(readerReason(reader, language), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    if (requested) OutlinedButton(onClick = {}, enabled = false) { Text("Request sent") }
                    else Button(onClick = { actions.onAddFriend(reader) }) { Text("Add friend") }
                    TextButton(onClick = { actions.onProfile(reader.person.id) }) { Text("View profile") }
                }
            }
        }
    }
}

@Composable
private fun AbstractCard(text: String, onShare: ((String) -> Unit)?) {
    val clipboard = LocalClipboardManager.current
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(start = 40.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy") }
                if (onShare != null) TextButton(onClick = { onShare(text) }) { Text("Share") }
            }
        }
    }
}

@Composable
private fun Chips(labels: List<String>, enabled: Boolean, onChip: (String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEach { label -> AssistChip(onClick = { onChip(label) }, label = { Text(label) }, enabled = enabled) }
    }
}

@Composable
private fun Input(enabled: Boolean, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = { if (enabled && text.isNotBlank()) { onSend(text); text = "" } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Ask BookNerd…") },
            modifier = Modifier.weight(1f),
            maxLines = 3,
            // The keyboard's action key sends, like the button (a chat message rarely needs a new line).
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
        )
        IconButton(onClick = submit, enabled = enabled) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}
