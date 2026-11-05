package com.mcosta.mybooks.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.ui.theme.supportsWallpaperColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * [accountLabel] / [onOpenAccount]: the community Account row ("Join the MyBooks community" or
 * "@username"); both null when the social features aren't configured.
 */
fun SettingsScreen(
    viewModel: SettingsViewModel,
    /** Null when opened from the bottom bar: no back arrow. */
    onBack: (() -> Unit)?,
    accountLabel: String? = null,
    onOpenAccount: (() -> Unit)? = null,
) {
    val message by viewModel.message.collectAsStateWithLifecycle()
    val coverProgress by viewModel.coverProgress.collectAsStateWithLifecycle()
    val followWallpaper by viewModel.followWallpaper.collectAsStateWithLifecycle()
    val alertsOn by viewModel.alertsOn.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (accountLabel != null && onOpenAccount != null) {
                Text("Community", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = onOpenAccount, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Person, null)
                    Text("  $accountLabel")
                }
                HorizontalDivider()
            }
            if (supportsWallpaperColors) {
                Text("Appearance", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Follow my wallpaper's colours", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Off: the book colours (cream paper, dark ink, teal).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = followWallpaper, onCheckedChange = viewModel::setFollowWallpaper)
                }
                HorizontalDivider()
            }
            if (viewModel.alertsAvailable) {
                Text("Notifications", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("New books from your authors", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Once a month, when New in Portugal has books by authors you have, or from publishers you read most.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = alertsOn, onCheckedChange = viewModel::setAlerts)
                }
                HorizontalDivider()
            }
            Text("Backup", style = MaterialTheme.typography.titleMedium)
            Text(
                "Save your whole library, including cover photos, to a .zip file, or restore it " +
                    "(older .json backups work too). Importing merges: matching books are updated, " +
                    "others are added. Nothing is deleted. Your phone also backs up the library, settings and cover " +
                    "photos by itself (to your Google account on Android, iCloud on iPhone, when backup is on in the " +
                    "phone's settings) and brings them back on a new phone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = viewModel::export,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Upload, null)
                Text("  Export library")
            }
            OutlinedButton(
                onClick = viewModel::import,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Download, null)
                Text("  Import library")
            }
            HorizontalDivider()
            Text("Goodreads", style = MaterialTheme.typography.titleMedium)
            Text(
                "Goodreads can't sync automatically, but it can swap files. To bring your Goodreads books here, " +
                    "export your library on goodreads.com (My Books, Import and export, Export Library) and import " +
                    "that file. Matching books take Goodreads' shelf, rating, dates and review; your covers, notes " +
                    "and progress stay. Covers are then looked up. To update Goodreads, export for Goodreads and " +
                    "upload the file on the same page.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = viewModel::importGoodreads, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Download, null)
                Text("  Import from Goodreads")
            }
            OutlinedButton(onClick = viewModel::exportGoodreads, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Upload, null)
                Text("  Export for Goodreads")
            }
            HorizontalDivider()
            Text("Covers", style = MaterialTheme.typography.titleMedium)
            Text(
                "Look up a cover for every book with an ISBN but no cover, in the same places as when you add a book. " +
                    "Many Portuguese editions have none online; you can take a photo from the book's page.",
                style = MaterialTheme.typography.bodyMedium,
            )
            val running = coverProgress?.finished == false
            coverProgress?.let { p ->
                if (p.finished) {
                    Text("Last search: covers found for ${p.found} of ${p.total} books.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("Finding covers: ${p.done} of ${p.total} books (${p.found} found)…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total }, Modifier.fillMaxWidth())
                }
            }
            OutlinedButton(onClick = viewModel::findCovers, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Search, null)
                Text("  Find missing covers")
            }
        }
    }

    message?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            text = { Text(it) },
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
        )
    }
}
