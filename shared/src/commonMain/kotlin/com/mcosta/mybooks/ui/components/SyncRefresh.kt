package com.mcosta.mybooks.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * Pull-to-refresh around a scrolling list, while the library syncs: [onRefresh] pushes and pulls, and the spinner shows
 * until it returns. With [onRefresh] null (sync off) the list is shown as it is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncRefreshBox(onRefresh: (suspend () -> Unit)?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (onRefresh == null) {
        Box(modifier) { content() }
        return
    }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            if (!refreshing) scope.launch {
                refreshing = true
                try {
                    onRefresh()
                } finally {
                    refreshing = false
                }
            }
        },
        modifier = modifier,
    ) { content() }
}
