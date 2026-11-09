package com.mcosta.mybooks.ui.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mcosta.mybooks.sync.SyncStatus
import com.mcosta.mybooks.sync.label
import kotlinx.coroutines.delay
import kotlin.time.Clock
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.social.model.Visibility
import com.mcosta.mybooks.ui.LocalPlatformServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(viewModel: AccountViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val camera = LocalPlatformServices.current.camera
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmStopSharing by remember { mutableStateOf(false) }
    var name by rememberSaveable(state.profile?.displayName) { mutableStateOf(state.profile?.displayName.orEmpty()) }
    // "Synced 5 minutes ago" moves on while the screen is open.
    val now by produceState(Clock.System.now().toEpochMilliseconds(), state.sync?.status) {
        while (true) {
            value = Clock.System.now().toEpochMilliseconds()
            delay(20_000)
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.dismissMessage() }
    }
    LaunchedEffect(state.deleted, state.signedOut) {
        if (state.deleted || state.signedOut) onBack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Account") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        val profile = state.profile
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (profile == null) {
                if (!state.busy) {
                    Text("Your profile couldn't be loaded.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = viewModel::refresh) { Text("Retry") }
                }
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PersonAvatar(profile.avatarUrl, size = 72.dp)
                Column {
                    Text("@${profile.username}", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                camera.takePhoto()?.let(viewModel::setAvatar)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                snackbar.showSnackbar("Couldn't take the photo: ${e.message ?: "unknown error"}")
                            }
                        }
                    }) { Text("Change photo") }
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(50) },
                label = { Text("Name") },
                singleLine = true,
                trailingIcon = {
                    if (name.trim() != profile.displayName) TextButton(onClick = { viewModel.saveDisplayName(name) }) { Text("Save") }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Who can see your reading activity", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(Visibility.FRIENDS to "Friends only", Visibility.PUBLIC to "Everyone").forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = profile.visibility == value,
                        onClick = { viewModel.setVisibility(value) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        label = { Text(label) },
                    )
                }
            }
            Text(
                "Anyone can find you by name or username. This setting decides who sees your shared reading activity.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Share my reading activity", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Starting, finishing, rating, reviewing and progress on your books. Notes stay private.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = profile.shareActivity,
                    onCheckedChange = { on -> if (on) viewModel.setShareActivity(true) else confirmStopSharing = true },
                    enabled = !state.busy,
                )
            }

            state.sync?.takeIf { it.status != SyncStatus.Off || it.merging != null }?.let { sync ->
                HorizontalDivider()
                SyncStatusSection(sync, now, onRetry = viewModel::retrySync)
            }

            HorizontalDivider()
            Text("Blocked people", style = MaterialTheme.typography.titleSmall)
            if (state.blocked.isEmpty()) {
                Text("Nobody", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.blocked.forEach { person ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PersonAvatar(person.avatarUrl, size = 32.dp)
                    Text(
                        "${person.displayName} (@${person.username})",
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    TextButton(onClick = { viewModel.unblock(person.id) }) { Text("Unblock") }
                }
            }

            HorizontalDivider()
            OutlinedButton(onClick = { viewModel.signOut() }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete account", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (state.askKeepLibrary) {
        KeepLibraryDialog(
            onKeep = { viewModel.signOut(keepLibrary = true) },
            onRemove = { viewModel.signOut(keepLibrary = false) },
            onDismiss = viewModel::cancelSignOut,
        )
    }
    if (confirmStopSharing) {
        AlertDialog(
            onDismissRequest = { confirmStopSharing = false },
            title = { Text("Stop sharing?") },
            text = { Text("This removes everything you've shared from the community.") },
            confirmButton = {
                TextButton(onClick = { confirmStopSharing = false; viewModel.setShareActivity(false) }) { Text("Stop sharing") }
            },
            dismissButton = { TextButton(onClick = { confirmStopSharing = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete account?") },
            text = {
                Text(
                    "Delete your MyBooks community account? Your profile, friends and requests are removed for good. " +
                        "The books on this phone stay."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.deleteAccount() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Settings → Account's "Library sync": where it stands ([now] is the current time, for "Synced 5 minutes ago"), with
 * Retry after a failure, the first sign-in's "Combining your libraries…", and what isn't syncing.
 */
@Composable
fun SyncStatusSection(info: SyncInfo, now: Long, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val merging = info.merging
    val failed = info.status is SyncStatus.Failed || info.status == SyncStatus.SignInAgain
    val icon = when {
        merging != null || info.status == SyncStatus.Syncing -> Icons.Filled.Sync
        info.status is SyncStatus.Offline -> Icons.Filled.CloudOff
        failed -> Icons.Filled.SyncProblem
        else -> Icons.Filled.CloudDone
    }
    val iconColor = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Library sync", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                when {
                    merging != null -> {
                        Text("Combining your libraries… ${merging.first} of ${merging.second}", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(
                            progress = { if (merging.second > 0) merging.first.toFloat() / merging.second else 0f },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                    info.status is SyncStatus.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Couldn't sync ·", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Retry") }
                    }
                    else -> Text(
                        info.status.label(now),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        val notes = buildList {
            if (info.coversFull) add("Cover photos not syncing: storage full")
            if (info.setAside > 0) add("${info.setAside} ${if (info.setAside == 1) "change" else "changes"} couldn't be synced")
        }
        for (note in notes) {
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 32.dp))
        }
    }
}

/** Signing out while the library syncs: keep a local copy of it ("Keep", the default) or erase it ("Remove"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeepLibraryDialog(onKeep: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    BasicAlertDialog(onDismissRequest = onDismiss) { KeepLibraryCard(onKeep, onRemove) }
}

/** [KeepLibraryDialog]'s card, drawn like an alert dialog (on its own for screenshots, which can't draw dialog windows). */
@Composable
fun KeepLibraryCard(onKeep: () -> Unit, onRemove: () -> Unit) {
    Surface(
        shape = AlertDialogDefaults.shape,
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
    ) {
        Column(Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 18.dp)) {
            Text(
                "Keep a copy of your library on this device?",
                style = MaterialTheme.typography.headlineSmall,
                color = AlertDialogDefaults.titleContentColor,
            )
            Text(
                "The library in your account stays as it is.",
                style = MaterialTheme.typography.bodyMedium,
                color = AlertDialogDefaults.textContentColor,
                modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
            )
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onKeep) { Text("Keep") }
            }
        }
    }
}
