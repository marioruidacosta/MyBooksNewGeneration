package com.mcosta.mybooks.reader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.domain.EbookFormat
import com.mcosta.mybooks.reader.ReaderChrome
import com.mcosta.mybooks.reader.canReadAloud
import com.mcosta.mybooks.reader.ReaderViewModel
import com.mcosta.mybooks.reader.ReadingLook
import com.mcosta.mybooks.reader.TocEntry
import kotlinx.coroutines.flow.StateFlow

/** What the bars can ask the reader to do. */
interface ReaderActions {
    fun goTo(entry: TocEntry)
    fun seek(progression: Double)
    fun setLook(look: ReadingLook)
    fun toggleReadAloud()
    fun playPause()
    fun cycleSpeed()
}

/**
 * The reader's top bar (back, title, contents, Aa for EPUB) and bottom bar (position slider, chapter and progress),
 * over the page; the sheets they open; and "Finished?".
 */
@Composable
fun ReaderBars(
    chrome: StateFlow<ReaderChrome>,
    look: StateFlow<ReadingLook>,
    readAloud: StateFlow<com.mcosta.mybooks.reader.ReadAloudUi>,
    viewModel: ReaderViewModel,
    actions: ReaderActions,
    onBack: () -> Unit,
) {
    val c by chrome.collectAsStateWithLifecycle()
    val currentLook by look.collectAsStateWithLifecycle()
    val finish by viewModel.finishPrompt.collectAsStateWithLifecycle()
    val marks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val shown by viewModel.shownHighlight.collectAsStateWithLifecycle()
    val found by viewModel.search.collectAsStateWithLifecycle()
    val aloud by readAloud.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(c.visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { sheet = BOOKMARKS }) { Icon(Icons.Outlined.Bookmarks, "Bookmarks") }
                    if (c.format == EbookFormat.EPUB) IconButton(onClick = { sheet = SEARCH }) { Icon(Icons.Filled.Search, "Search the book") }
                    if (c.format?.let { canReadAloud(it) } == true) IconButton(onClick = actions::toggleReadAloud) {
                        Icon(Icons.Filled.RecordVoiceOver, if (aloud.active) "Stop reading aloud" else "Read aloud")
                    }
                    if (c.contents.isNotEmpty()) IconButton(onClick = { sheet = CONTENTS }) { Icon(Icons.AutoMirrored.Filled.List, "Contents") }
                    if (c.format == EbookFormat.EPUB) IconButton(onClick = { sheet = LOOK }) { Icon(Icons.Filled.TextFields, "Text and colours") }
                }
            }
        }
        AnimatedVisibility(c.visible && c.label.isNotEmpty(), enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    if (aloud.active) Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = actions::playPause) {
                            if (aloud.playing) Icon(Icons.Filled.Pause, "Pause") else Icon(Icons.Filled.PlayArrow, "Read")
                        }
                        TextButton(onClick = actions::cycleSpeed) { Text(com.mcosta.mybooks.reader.speedLabel(aloud.speed)) }
                        Text("Reading aloud", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = actions::toggleReadAloud) { Icon(Icons.Filled.Close, "Stop reading aloud") }
                    }
                    // The slider follows the reading place, except while it is being dragged.
                    var dragging by remember { mutableStateOf(false) }
                    var dragged by remember { mutableFloatStateOf(0f) }
                    Slider(
                        value = if (dragging) dragged else c.progression.toFloat(),
                        onValueChange = { dragging = true; dragged = it },
                        onValueChangeFinished = { dragging = false; actions.seek(dragged.toDouble()) },
                    )
                    Text(c.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }
    }
    when (sheet) {
        CONTENTS -> ContentsSheet(c.contents, onGo = { sheet = null; actions.goTo(it) }, onDismiss = { sheet = null })
        LOOK -> LookSheet(currentLook, onChange = actions::setLook, onDismiss = { sheet = null })
        SEARCH -> SearchSheet(
            found,
            onSearch = viewModel::search,
            onMore = viewModel::searchMore,
            onOpen = { sheet = null; viewModel.goToPosition(it.position) },
            onDismiss = { sheet = null },
        )
        BOOKMARKS -> BookmarksSheet(
            marks,
            onAdd = { sheet = null; viewModel.bookmarkHere() },
            onOpen = { sheet = null; viewModel.open(it) },
            onDelete = viewModel::deleteBookmark,
            onDismiss = { sheet = null },
        )
    }
    shown?.let { quote ->
        AlertDialog(
            onDismissRequest = viewModel::closeHighlight,
            title = { Text("Highlight") },
            text = { Text("“${quote.text}”") },
            confirmButton = { TextButton(onClick = viewModel::closeHighlight) { Text("Close") } },
            dismissButton = { TextButton(onClick = viewModel::deleteShownHighlight) { Text("Delete") } },
        )
    }
    if (finish) {
        AlertDialog(
            onDismissRequest = viewModel::dismissFinish,
            title = { Text("Finished?") },
            text = { Text("You've reached the end. Mark it as Read, finished today?") },
            confirmButton = { TextButton(onClick = viewModel::markFinished) { Text("Mark as Read") } },
            dismissButton = { TextButton(onClick = viewModel::dismissFinish) { Text("Not now") } },
        )
    }
}

private const val CONTENTS = "contents"
private const val LOOK = "look"
private const val BOOKMARKS = "bookmarks"
private const val SEARCH = "search"
