package com.mcosta.mybooks.ui.physical

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.physical.onShelf
import com.mcosta.mybooks.physical.shelfIds

/** One bookcase from the front. A spine is too small to tap on a phone, so a shelf opens its list of books. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookcaseScreen(
    viewModel: PhysicalLibraryViewModel,
    bookcaseId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenShelf: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val case = viewModel.bookcase(bookcaseId)
    LaunchedEffect(case == null) { if (case == null) onBack() }
    if (case == null) return
    val count = case.shelfIds().sumOf { state.books.onShelf(it).size }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(case.name, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (!case.readOnly) IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit bookcase") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${case.room} · ${countText(count, "book", "books")}", style = MaterialTheme.typography.bodyMedium)
            BookcaseDrawing(
                case, { state.books.onShelf(it) }, Modifier.fillMaxWidth(),
                labels = true, shelfHeight = 72.dp, onShelf = onOpenShelf,
            )
            Text("Tap a shelf to see and arrange its books.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
