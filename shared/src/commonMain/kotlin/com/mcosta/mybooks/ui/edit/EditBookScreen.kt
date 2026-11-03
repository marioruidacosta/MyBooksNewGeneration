package com.mcosta.mybooks.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.mcosta.mybooks.data.MAX_REVIEW_LENGTH
import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.ui.components.rememberCoverPhotoTaker
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.ui.components.BookCover
import com.mcosta.mybooks.ui.components.RatingStars
import com.mcosta.mybooks.ui.components.StatusSelector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditBookScreen(
    viewModel: EditBookViewModel,
    isNew: Boolean,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    /** Signed in to the community with a profile: the public Review field is shown. */
    showReview: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val f = state.form
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val takePhoto = rememberCoverPhotoTaker(
        onPhoto = { ref -> viewModel.update { it.copy(coverUrl = ref) } },
        onError = { scope.launch { snackbar.showSnackbar(it) } },
    )

    LaunchedEffect(state.savedId) { state.savedId?.let(onSaved) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "Add book" else "Edit book") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    TextButton(
                        onClick = viewModel::save,
                        enabled = !state.loading && !state.saving && state.savedId == null,
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loading) {
                Text("Looking up book…")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BookCover(f.coverUrl, Modifier.width(80.dp).height(120.dp), title = f.title.ifBlank { null }, authors = f.authors)
                    TextButton(onClick = takePhoto) { Text("Take photo") }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Title *", f.title) { v -> viewModel.update { it.copy(title = v) } }
                    Field("Authors", f.authors) { v -> viewModel.update { it.copy(authors = v) } }
                }
            }
            StatusSelector(f.status, { s -> viewModel.update { it.copy(status = s) } }, Modifier.fillMaxWidth())
            RatingStars(f.rating, onRate = { r -> viewModel.update { it.copy(rating = r) } })
            Field("ISBN", f.isbn, keyboardOptions = isbnKeyboardOptions) { v -> viewModel.update { it.copy(isbn = v) } }
            Field("Publisher", f.publisher) { v -> viewModel.update { it.copy(publisher = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Year", f.year, KeyboardType.Number, Modifier.weight(1f)) { v -> viewModel.update { it.copy(year = v) } }
                Field("Pages", f.pages, KeyboardType.Number, Modifier.weight(1f)) { v -> viewModel.update { it.copy(pages = v) } }
            }
            if (f.coverUrl.startsWith(CoverStore.PREFIX)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cover photo", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.update { it.copy(coverUrl = "") } }) { Text("Remove") }
                }
            } else {
                Field("Cover URL", f.coverUrl, KeyboardType.Uri) { v -> viewModel.update { it.copy(coverUrl = v) } }
            }
            Field("Description", f.description, singleLine = false) { v -> viewModel.update { it.copy(description = v) } }
            Field("Notes (private)", f.notes, singleLine = false) { v -> viewModel.update { it.copy(notes = v) } }
            if (showReview) {
                Field("Review (shared with friends)", f.review, singleLine = false) { v ->
                    viewModel.update { it.copy(review = v.take(MAX_REVIEW_LENGTH)) }
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier.fillMaxWidth(),
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = keyboardOptions,
        modifier = modifier,
    )
}

/** Not numeric: ISBN-10s can end in "X". */
internal val isbnKeyboardOptions = KeyboardOptions(
    keyboardType = KeyboardType.Ascii,
    capitalization = KeyboardCapitalization.Characters,
)
