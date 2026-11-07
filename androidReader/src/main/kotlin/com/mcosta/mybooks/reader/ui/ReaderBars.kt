package com.mcosta.mybooks.reader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.reader.ReaderChrome
import com.mcosta.mybooks.reader.ReaderViewModel
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.layout.Row

/** The reader's top bar (back, title) and bottom bar (chapter and progress), over the page; and "Finished?". */
@Composable
fun ReaderBars(chrome: StateFlow<ReaderChrome>, viewModel: ReaderViewModel, onBack: () -> Unit) {
    val c by chrome.collectAsStateWithLifecycle()
    val finish by viewModel.finishPrompt.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(c.visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        AnimatedVisibility(c.visible && c.label.isNotEmpty(), enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Text(
                    c.label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
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
