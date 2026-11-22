package com.mcosta.mybooks.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/** The bottom bar's last tab: what doesn't need a tab of its own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(onOpenNews: () -> Unit, onOpenSettings: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("More") }) }) { padding ->
        Column(Modifier.padding(padding)) {
            MoreRow(Icons.Filled.Newspaper, "Book news", "Stories and events from the book world", onOpenNews)
            HorizontalDivider()
            MoreRow(Icons.Filled.Settings, "Settings", "Account, appearance, notifications and backup", onOpenSettings)
        }
    }
}

@Composable
private fun MoreRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
