package com.mcosta.mybooks.ui.ebooks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.data.ebooks.Classic
import com.mcosta.mybooks.ui.components.BookCover

/** Free classics: search Project Gutenberg's public-domain books, download one into the library, read it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassicsScreen(viewModel: ClassicsViewModel, onBack: () -> Unit, onOpenBook: (Long) -> Unit, onRead: ((Long) -> Unit)?) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(state.query) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.dismissMessage() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Free classics") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Title or author") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search(query) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            when (val s = state.search) {
                ClassicsState.Idle -> Hint("Free public-domain books from Project Gutenberg, in many languages. Search by title or author.")
                ClassicsState.Searching -> Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("Searching Project Gutenberg…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                ClassicsState.Failed -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Couldn't reach Project Gutenberg.", style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = viewModel::retry) { Text("Retry") }
                }
                is ClassicsState.Results -> if (s.books.isEmpty()) {
                    Hint("No free books found for “${state.query}”.")
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(s.books, key = { it.id }) { classic ->
                            ClassicRow(
                                classic,
                                downloading = classic.id in state.downloading,
                                bookId = state.downloaded[classic.id],
                                onDownload = { viewModel.download(classic) },
                                onOpenBook = onOpenBook,
                                onRead = onRead,
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))

@Composable
private fun ClassicRow(
    classic: Classic,
    downloading: Boolean,
    bookId: Long?,
    onDownload: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onRead: ((Long) -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = bookId != null) { bookId?.let(onOpenBook) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(classic.coverUrl, Modifier.width(40.dp).height(60.dp), title = classic.title, authors = classic.authors.joinToString(", "))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(classic.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (classic.authors.isNotEmpty()) {
                Text(classic.authors.joinToString(", "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(classic.languages.joinToString(" · ") { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            downloading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            bookId != null && onRead != null -> TextButton(onClick = { onRead(bookId) }) { Text("Read") }
            bookId != null -> TextButton(onClick = { onOpenBook(bookId) }) { Text("Open") }
            else -> TextButton(onClick = onDownload) { Text("Download") }
        }
    }
}
