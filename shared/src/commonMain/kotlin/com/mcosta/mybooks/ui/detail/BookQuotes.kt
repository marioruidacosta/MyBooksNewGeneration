package com.mcosta.mybooks.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.MAX_QUOTE_LENGTH
import com.mcosta.mybooks.domain.Quote
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.domain.tidyPageText
import com.mcosta.mybooks.ui.LocalPlatformServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What the quote dialog is showing: a new quote (maybe read from a photo) or one being changed. */
private sealed interface QuoteEdit {
    data class New(val text: String, val page: String) : QuoteEdit
    data class Existing(val quote: Quote) : QuoteEdit
}

/**
 * The book's quotes: tap one to change or delete it; "Add quote" to type one, "From a photo" to photograph the page
 * and keep the part you want (only where the phone can read text).
 */
@Composable
internal fun QuotesSection(
    book: Book,
    quotes: List<Quote>,
    onAdd: (text: String, page: String) -> Unit,
    onUpdate: (Quote, text: String, page: String) -> Unit,
    onDelete: (Quote) -> Unit,
    onMessage: (String) -> Unit,
) {
    val services = LocalPlatformServices.current
    val reader = services.pageReader
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<QuoteEdit?>(null) }
    var reading by remember { mutableStateOf(false) }
    // A reading book's current page is the likeliest place of a new quote.
    val pageNow = book.currentPage?.takeIf { book.status == ReadingStatus.READING && it > 0 }?.toString().orEmpty()

    SectionCard("Quotes") {
        for ((i, quote) in quotes.withIndex()) {
            if (i > 0) HorizontalDivider()
            Column(
                Modifier.fillMaxWidth().clickable { editing = QuoteEdit.Existing(quote) }.padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("“${quote.text}”", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                quote.page?.let {
                    Text("p. $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { editing = QuoteEdit.New("", pageNow) }) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Text("Add quote", Modifier.padding(start = 8.dp))
            }
            if (reader != null) {
                TextButton(
                    enabled = !reading,
                    onClick = {
                        scope.launch {
                            try {
                                val photo = services.camera.takePhoto() ?: return@launch
                                reading = true
                                val text = tidyPageText(reader.read(photo)).take(MAX_QUOTE_LENGTH)
                                if (text.isBlank()) onMessage("No text found in the photo")
                                editing = QuoteEdit.New(text, pageNow)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                onMessage("Couldn't read the page: ${e.message ?: "unknown error"}")
                            } finally {
                                reading = false
                            }
                        }
                    },
                ) {
                    if (reading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(18.dp))
                    Text(if (reading) "Reading the page…" else "From a photo", Modifier.padding(start = 8.dp))
                }
            }
        }
    }

    when (val e = editing) {
        is QuoteEdit.New -> QuoteDialog(
            title = "New quote",
            initialText = e.text,
            initialPage = e.page,
            fromPhoto = e.text.isNotEmpty(),
            onSave = { text, page -> onAdd(text, page); editing = null },
            onDelete = null,
            onDismiss = { editing = null },
        )
        is QuoteEdit.Existing -> QuoteDialog(
            title = "Quote",
            initialText = e.quote.text,
            initialPage = e.quote.page?.toString().orEmpty(),
            fromPhoto = false,
            onSave = { text, page -> onUpdate(e.quote, text, page); editing = null },
            onDelete = { onDelete(e.quote); editing = null },
            onDismiss = { editing = null },
        )
        null -> Unit
    }
}

@Composable
private fun QuoteDialog(
    title: String,
    initialText: String,
    initialPage: String,
    fromPhoto: Boolean,
    onSave: (String, String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    var page by rememberSaveable { mutableStateOf(initialPage) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fromPhoto) {
                    Text(
                        "Read from the photo. Delete what isn't part of the quote, and fix any misread words.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(MAX_QUOTE_LENGTH) },
                    placeholder = { Text("The passage you want to keep") },
                    minLines = 4,
                    maxLines = 12,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = page,
                    onValueChange = { v -> page = v.filter { it.isDigit() }.take(5) },
                    label = { Text("Page") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(120.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text, page) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
