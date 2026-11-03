package com.mcosta.mybooks.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import com.mcosta.mybooks.ui.formatDate
import kotlinx.coroutines.launch
import kotlin.time.Clock
import com.mcosta.mybooks.ui.components.rememberCoverPhotoTaker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.data.MAX_REVIEW_LENGTH
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.share.SharingState
import com.mcosta.mybooks.ui.components.BookCover
import com.mcosta.mybooks.ui.components.RatingStars
import com.mcosta.mybooks.ui.components.StatusSelector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    viewModel: BookDetailViewModel,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    /** Community sharing: the Review field needs a profile, "Don't share this book" needs sharing on. */
    sharing: SharingState = SharingState(),
) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val notes by viewModel.notesDraft.collectAsStateWithLifecycle()
    val review by viewModel.reviewDraft.collectAsStateWithLifecycle()
    val shareExcluded by viewModel.shareExcluded.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var pickingReadDate by remember { mutableStateOf(false) }

    LaunchedEffect(deleted) { if (deleted) onBack() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val takePhoto = rememberCoverPhotoTaker(
        onPhoto = viewModel::setCover,
        onError = { scope.launch { snackbar.showSnackbar(it) } },
    )
    // Save the drafts if the app goes to the background.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.saveNotes(); viewModel.saveReview() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { viewModel.saveNotes(); viewModel.saveReview(); onEdit() }) { Icon(Icons.Filled.Edit, "Edit") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
                },
            )
        },
    ) { padding ->
        val b = book
        if (b == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                if (!deleted) CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Header(b, onAddPhoto = takePhoto)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusSelector(b.status, viewModel::setStatus, Modifier.fillMaxWidth())
                SectionCard("Your score") { RatingStars(b.rating, onRate = viewModel::rate, size = 32.dp) }
                if (b.status == ReadingStatus.READ) SectionCard("Reading dates") {
                    val read = b.dateRead
                    val text = when {
                        read == null -> "Read date not set: it counts toward no year's challenge"
                        b.dateStarted != null -> "Read from ${formatDate(b.dateStarted)} to ${formatDate(read)}"
                        else -> "Read on ${formatDate(read)}"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { pickingReadDate = true }) { Text(if (read == null) "Set" else "Change") }
                    }
                    if (pickingReadDate) {
                        DayPicker(
                            initial = read ?: Clock.System.now().toEpochMilliseconds(),
                            onPicked = { viewModel.setDateRead(it); pickingReadDate = false },
                            onDismiss = { pickingReadDate = false },
                        )
                    }
                }
                if (b.status == ReadingStatus.READING) SectionCard("Reading") { ReadingSection(b, viewModel) }
                b.description?.takeIf { it.isNotBlank() }?.let { SectionCard("Description") { Description(it) } }
                SectionCard("Notes") {
                    OutlinedTextField(
                        value = notes,
                        onValueChange = viewModel::editNotes,
                        placeholder = { Text("Private: only you see them") },
                        minLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (!it.isFocused) viewModel.saveNotes() },
                    )
                }
                if (sharing.hasProfile || sharing.activeOwner != null) SectionCard("Sharing") {
                    if (sharing.hasProfile) {
                        OutlinedTextField(
                            value = review,
                            onValueChange = viewModel::editReview,
                            label = { Text("Review (shared with friends)") },
                            supportingText = { Text("${review.length} / $MAX_REVIEW_LENGTH") },
                            minLines = 3,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { if (!it.isFocused) viewModel.saveReview() },
                        )
                    }
                    if (sharing.activeOwner != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Don't share this book", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Switch(checked = shareExcluded, onCheckedChange = viewModel::setShareExcluded)
                        }
                    }
                }
                Text(
                    "Added ${formatDate(b.dateAdded)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this book?") },
            text = { Text("It will be removed from your library.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/**
 * A large cover over a softly blurred copy of it, fading into the page (Android blurs from version 12; earlier
 * it's a shaded wash), then the title and authors. Swiping the cover shows the book's details in its place.
 */
@Composable
private fun Header(b: Book, onAddPhoto: () -> Unit) {
    val pages = rememberPagerState { 2 }
    Box(Modifier.fillMaxWidth()) {
        BookCover(b.coverUrl, Modifier.matchParentSize().blur(28.dp).alpha(0.5f), title = b.title, authors = b.authors)
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface.copy(alpha = 0.2f), MaterialTheme.colorScheme.surface)),
            ),
        )
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HorizontalPager(pages, modifier = Modifier.fillMaxWidth().height(COVER_HEIGHT)) { page ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (page == 0) {
                        Surface(shape = RoundedCornerShape(6.dp), shadowElevation = 10.dp) {
                            BookCover(b.coverUrl, Modifier.width(COVER_HEIGHT * 2 / 3).height(COVER_HEIGHT), title = b.title, authors = b.authors)
                        }
                    } else {
                        DetailsPanel(b)
                    }
                }
            }
            PageDots(current = pages.currentPage, count = pages.pageCount)
            // No cover found: photograph the book.
            if (b.coverUrl.isNullOrBlank()) TextButton(onClick = onAddPhoto) { Text("Add a cover photo") }
            Text(b.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            if (b.authors.isNotBlank()) Text(b.authors, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }
}

private val COVER_HEIGHT = 225.dp

/** The book's details, the size of the cover: label and value per line. */
@Composable
private fun DetailsPanel(b: Book) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        shadowElevation = 10.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxHeight().widthIn(max = 300.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Details", style = MaterialTheme.typography.titleMedium)
            for ((label, value) in bookDetails(b)) {
                Row {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(84.dp),
                    )
                    Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Which of the header's panels is showing. */
@Composable
private fun PageDots(current: Int, count: Int) {
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val color = if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        }
    }
}

/** A titled card holding one part of the book page. */
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Description(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.clickable { expanded = !expanded }) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (!expanded) Text("More", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
    }
}
