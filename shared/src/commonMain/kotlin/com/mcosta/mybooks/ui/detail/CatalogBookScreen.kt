package com.mcosta.mybooks.ui.detail

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.ui.components.BookCover
import com.mcosta.mybooks.ui.library.label
import com.mcosta.mybooks.ui.releases.tryOpen
import kotlinx.coroutines.launch

/** A catalogue book's page (from "More by …"): what it is, add it to the library, and where to buy it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogBookScreen(
    viewModel: CatalogBookViewModel,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
    /** "Not interested": never suggested again (then back). */
    onNotInterested: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val book = viewModel.book
    val uri = LocalUriHandler.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(title = {}, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        },
    ) { padding ->
        if (book == null) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("This book is no longer available. Go back and open it again.", textAlign = TextAlign.Center)
            }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth()) {
                BookCover(book.coverUrl, Modifier.matchParentSize().blur(28.dp).alpha(0.5f), title = book.title, authors = book.authors)
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface.copy(alpha = 0.2f), MaterialTheme.colorScheme.surface)),
                    ),
                )
                Column(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Surface(shape = RoundedCornerShape(6.dp), shadowElevation = 10.dp) {
                        BookCover(book.coverUrl, Modifier.width(150.dp).height(225.dp), title = book.title, authors = book.authors)
                    }
                    Text(book.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    Text(book.authors, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    val facts = listOfNotNull(book.publisher, book.year?.toString(), book.pageCount?.let { "$it pages" })
                    if (facts.isNotEmpty()) {
                        Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    book.isbn?.let { Text("ISBN $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val copy = state.inLibrary
                if (copy != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("In your library · ${copy.status.label()}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onOpenBook(copy.id) }) { Text("Open") }
                    }
                } else if (state.loaded) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.add(ReadingStatus.TO_READ) }, modifier = Modifier.weight(1f)) { Text("Add to To read") }
                        OutlinedButton(onClick = { viewModel.add(ReadingStatus.WISHLIST) }, modifier = Modifier.weight(1f)) { Text("Add to To buy") }
                    }
                    if (onNotInterested != null) {
                        TextButton(onClick = onNotInterested) { Text("Not interested") }
                    }
                }
                book.description?.takeIf { it.isNotBlank() }?.let { SectionCard("Description") { Description(it) } }
                if (state.stores.isNotEmpty()) {
                    WhereToBuy(state.stores, byIsbn = book.isbn != null, onCantOpen = { scope.launch { snackbar.showSnackbar("No app can open this link") } })
                }
            }
        }
    }
}
