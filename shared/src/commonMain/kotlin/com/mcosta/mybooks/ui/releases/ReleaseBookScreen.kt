package com.mcosta.mybooks.ui.releases

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.ui.detail.BookHeader
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseBookScreen(
    viewModel: ReleaseBookViewModel,
    onBack: () -> Unit,
    /** Opens one of the library's books by the author (from the header's author panel). */
    onOpenBook: (Long) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val author by viewModel.author.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val cantOpen = { scope.launch { snackbar.showSnackbar("No app on this phone can open the link") }; Unit }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.dismissMessage() }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        val book = state.book
        if (book == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.loaded) Message(LOAD_ERROR) else CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            BookHeader(
                state.coverUrl, book.title, book.authors.joinToString(", "), releaseDetails(book), author,
                onAuthorShown = viewModel::loadAuthor, onOpenBook = onOpenBook,
            )
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                state.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

                HorizontalDivider()
                Text("Add to my books", style = MaterialTheme.typography.titleSmall)
                if (state.inLibrary) {
                    Text("Already in your library", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { viewModel.addToLibrary(ReadingStatus.TO_READ) }) { Text("To read") }
                        OutlinedButton(onClick = { viewModel.addToLibrary(ReadingStatus.WISHLIST) }) { Text("To buy") }
                    }
                }

                HorizontalDivider()
                Text("Where to buy", style = MaterialTheme.typography.titleSmall)
                state.stores.forEach { store ->
                    OutlinedButton(onClick = { if (!uri.tryOpen(store.url)) cantOpen() }, modifier = Modifier.fillMaxWidth()) {
                        Text(store.name, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
                    }
                }
                book.link?.let { link ->
                    TextButton(onClick = { if (!uri.tryOpen(link)) cantOpen() }) { Text("View at the National Library") }
                }
            }
        }
    }
}
