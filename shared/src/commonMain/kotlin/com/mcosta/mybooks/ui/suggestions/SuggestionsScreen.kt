package com.mcosta.mybooks.ui.suggestions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.data.suggestions.Suggestion
import com.mcosta.mybooks.ui.components.BookCover

/** All suggestions, a row per source, each book with its reason; refresh works them out again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionsScreen(viewModel: SuggestionsViewModel, onBack: () -> Unit, onOpen: (Suggestion) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Suggested for you") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.loading) { Icon(Icons.Filled.Refresh, "Refresh suggestions") }
                },
            )
        },
    ) { padding ->
        val sections = state.suggestions.sections
        when {
            sections.isEmpty() && state.loading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("Looking for books you’ll like…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            sections.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No suggestions yet. Score the books you’ve read (4–5★ for the ones you loved) and suggestions will follow.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (state.loading) item { CircularProgressIndicator(Modifier.padding(16.dp).height(24.dp)) }
                for ((source, list) in sections) {
                    item(key = source.name) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(source.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
                            SuggestionRow(list, onOpen, onNotInterested = viewModel::hide)
                        }
                    }
                }
                item {
                    Text(
                        "Press and hold a book for “Not interested”.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

/** A row of suggestion cards; a long press offers "Not interested". */
@Composable
fun SuggestionRow(suggestions: List<Suggestion>, onOpen: (Suggestion) -> Unit, onNotInterested: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(suggestions, key = { it.source.name + it.key }) { s -> SuggestionCard(s, onOpen, onNotInterested) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestionCard(s: Suggestion, onOpen: (Suggestion) -> Unit, onNotInterested: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.width(104.dp).combinedClickable(onClick = { onOpen(s) }, onLongClick = { menu = true }, onLongClickLabel = "Not interested"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Surface(shape = RoundedCornerShape(4.dp), shadowElevation = 3.dp, modifier = Modifier.width(104.dp).height(156.dp)) {
                BookCover(s.coverUrl, Modifier.fillMaxSize(), title = s.title, authors = s.authors)
            }
            Text(s.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                s.reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Not interested") }, onClick = { menu = false; onNotInterested(s.key) })
        }
    }
}

/** Home's "Suggested for you": the best of each source, with See all. */
@Composable
fun SuggestedForYou(state: SuggestionsState, onOpen: (Suggestion) -> Unit, onNotInterested: (String) -> Unit, onSeeAll: () -> Unit) {
    val mixed = state.suggestions.mix()
    if (mixed.isEmpty() && !state.loading) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = mixed.isNotEmpty(), onClick = onSeeAll).padding(start = 16.dp, end = 16.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Suggested for you", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (mixed.isNotEmpty()) Text("See all", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (mixed.isEmpty()) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Looking for books you’ll like…", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            SuggestionRow(mixed, onOpen, onNotInterested)
        }
    }
}

