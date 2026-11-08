package com.mcosta.mybooks.ui.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.social.model.FeedEntry
import com.mcosta.mybooks.social.model.FeedScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    viewModel: ActivityViewModel,
    /** Null when opened from the bottom bar: no back arrow. */
    onBack: (() -> Unit)?,
    onJoin: () -> Unit,
    onFindPeople: () -> Unit,
    onOpenPerson: (String) -> Unit,
    /** Friends and requests (the Community tab's second screen). */
    onOpenFriends: (() -> Unit)? = null,
    /** Opens a book of the user's library (from "✓ In your library"). */
    onOpenLibraryBook: (Long) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var reporting by remember { mutableStateOf<FeedEntry?>(null) }
    var seeAllReading by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.dismissMessage() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Community") },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { if (onOpenFriends != null) IconButton(onClick = onOpenFriends) { Icon(Icons.Filled.Group, "Friends") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!state.signedIn) {
                if (state.loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (state.error != null) {
                    ErrorState(state.error!!, viewModel::refresh)
                } else {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("See what your friends are reading", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Join the community to follow friends' reading and share yours. Your library stays on this phone.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = onJoin) { Text("Join the MyBooks community") }
                    }
                }
                return@Column
            }
            val tabs = listOf(FeedScope.FRIENDS to "Friends", FeedScope.EVERYONE to "Everyone")
            PrimaryTabRow(selectedTabIndex = tabs.indexOfFirst { it.first == state.scope }) {
                tabs.forEach { (scope, label) ->
                    Tab(selected = state.scope == scope, onClick = { viewModel.selectScope(scope) }, text = { Text(label) })
                }
            }
            PullToRefreshBox(isRefreshing = state.loading, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
                when {
                    state.error != null && state.entries.isEmpty() -> ErrorState(state.error!!, viewModel::refresh)
                    state.entries.isEmpty() && !state.loading -> EmptyFeed(
                        noFriends = state.scope == FeedScope.FRIENDS && !state.hasFriends,
                        onFindPeople = onFindPeople,
                    )
                    else -> CommunityFeed(
                        state,
                        FeedCallbacks(
                            onPerson = onOpenPerson,
                            onBook = viewModel::openBook,
                            onWantToRead = viewModel::wantToRead,
                            onWantToBuy = viewModel::wantToBuy,
                            onOpenMine = { book -> viewModel.openMine(book, onOpenLibraryBook) },
                            onLike = viewModel::toggleLike,
                            onLikers = viewModel::showLikers,
                            onReport = { reporting = it },
                            onLoadMore = viewModel::loadMore,
                            onSeeAllReading = { seeAllReading = true },
                        ),
                    )
                }
            }
        }
    }

    state.likers?.let { people ->
        LikersSheet(people, onPerson = { viewModel.dismissLikers(); onOpenPerson(it) }, onDismiss = viewModel::dismissLikers)
    }
    state.openBook?.let { book ->
        FeedBookSheet(book, state.openBookInLibrary, onAdd = viewModel::addToLibrary, onDismiss = viewModel::closeBook)
    }
    if (seeAllReading) {
        FriendsReadingSheet(state.reading, onBook = { seeAllReading = false; viewModel.openBook(it) }, onDismiss = { seeAllReading = false })
    }
    reporting?.let { entry ->
        ReportDialog(
            entry.actor.displayName,
            onSend = { reason, details -> reporting = null; viewModel.report(entry, reason, details) },
            onDismiss = { reporting = null },
        )
    }
}

@Composable
private fun EmptyFeed(noFriends: Boolean, onFindPeople: () -> Unit) {
    // A scrollable list so pull-to-refresh also works when there's nothing yet.
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (noFriends) {
                    Text("Add friends to see what they're reading.", style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onFindPeople) { Text("Find people") }
                } else {
                    Text("No activity yet", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(message, style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}
