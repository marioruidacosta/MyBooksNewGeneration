package com.mcosta.mybooks.ui.news

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mcosta.mybooks.data.news.BookEvent
import com.mcosta.mybooks.data.news.NewsItem
import com.mcosta.mybooks.data.news.NewsLanguage
import com.mcosta.mybooks.data.news.usually
import com.mcosta.mybooks.ui.components.SyncRefreshBox
import com.mcosta.mybooks.ui.formatDate
import com.mcosta.mybooks.ui.releases.Message
import com.mcosta.mybooks.ui.releases.tryOpen
import kotlinx.coroutines.launch

/** Opens a link in the browser, saying so in [snackbar] when nothing on the phone can open it. */
@Composable
fun rememberLinkOpener(snackbar: SnackbarHostState): (String) -> Unit {
    val uris = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    return { url -> if (!uris.tryOpen(url)) scope.launch { snackbar.showSnackbar("Couldn't open the link") } }
}

/** The Book news screen: stories grouped by day (with language chips), and the book calendar in a second tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsScreen(viewModel: NewsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val open = rememberLinkOpener(snackbar)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Book news") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Stories") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Book calendar") })
            }
            when {
                tab == 1 -> CalendarList(state.events, open)
                state.loading && state.items.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.items.isEmpty() -> Message(state.error ?: NEWS_ERROR, onRetry = viewModel::retry)
                else -> SyncRefreshBox(viewModel::refresh, Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item(key = "chips") {
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(NewsLanguage.entries, key = { it.name }) { language ->
                                    FilterChip(
                                        selected = state.language == language,
                                        onClick = { viewModel.setLanguage(language) },
                                        label = { Text(language.label) },
                                    )
                                }
                            }
                        }
                        state.savedAt?.let { at ->
                            item(key = "saved") {
                                Text(
                                    "Showing news from ${formatDate(at.toEpochMilliseconds())}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                        }
                        for (day in state.days) {
                            item(key = "day:${day.label}") {
                                Text(
                                    day.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                                )
                            }
                            items(day.items, key = { it.id }) { story ->
                                StoryRow(story, state.sourceName(story.source), onClick = { open(story.url) })
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A story: source, title in serif, a two-line summary (when there is one) and an optional thumbnail. */
@Composable
private fun StoryRow(story: NewsItem, source: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(source, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(story.title, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
            if (story.summary.isNotBlank()) {
                Text(
                    story.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        story.image?.let { image ->
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
    }
}

@Composable
private fun CalendarList(events: List<BookEvent>, open: (String) -> Unit) {
    if (events.isEmpty()) {
        Message("The book calendar is not available right now.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(events, key = { it.name }) { event ->
            Column(
                Modifier.fillMaxWidth().clickable { open(event.url) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(event.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(event.usually(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        }
    }
}

/** Home's "Book news": the newest headlines, with See all. Nothing is shown until there are stories. */
@Composable
fun HomeNewsCard(state: NewsState, onOpenStory: (String) -> Unit, onSeeAll: () -> Unit) {
    val latest = state.latest
    if (latest.isEmpty()) return
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onSeeAll).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Book news", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("See all", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
        }
        OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            latest.forEachIndexed { i, story ->
                if (i > 0) HorizontalDivider()
                Column(
                    Modifier.fillMaxWidth().clickable { onOpenStory(story.url) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(state.sourceName(story.source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(story.title, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Serif, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
