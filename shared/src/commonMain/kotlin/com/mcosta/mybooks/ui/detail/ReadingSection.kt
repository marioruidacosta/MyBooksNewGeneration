package com.mcosta.mybooks.ui.detail

import com.mcosta.mybooks.ui.components.ProgressField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.ui.formatDate
import com.mcosta.mybooks.ui.localDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/** Start date and progress for a book being read. */
@Composable
fun ReadingSection(book: Book, viewModel: BookDetailViewModel) {
    val error by viewModel.progressError.collectAsStateWithLifecycle()
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var byPages by rememberSaveable(book.id) { mutableStateOf(book.currentPage != null || book.progressPercent == null) }
    var input by rememberSaveable(book.id) { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val submit = {
        viewModel.updateProgress(input, byPages)
        input = ""
        focus.clearFocus()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                book.dateStarted?.let { "Started on ${formatDate(it)}" } ?: "Start date not set",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { pickingDate = true }) { Text("Change") }
        }

        Text("Progress", style = MaterialTheme.typography.labelLarge)
        Text(progressText(book) ?: "No progress yet", style = MaterialTheme.typography.bodyMedium)
        book.progressPercent?.let { LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth()) }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProgressField(input, { input = it }, hint = if (byPages) "Page" else "%", isError = error != null, onDone = { submit() })
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                SegmentedButton(
                    selected = byPages,
                    onClick = { byPages = true },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    label = { Text("Pages") },
                )
                SegmentedButton(
                    selected = !byPages,
                    onClick = { byPages = false },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    label = { Text("%") },
                )
            }
            Button(onClick = submit, enabled = input.isNotEmpty()) { Text("Update") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }

    if (pickingDate) {
        DayPicker(
            initial = book.dateStarted ?: Clock.System.now().toEpochMilliseconds(),
            onPicked = { viewModel.setDateStarted(it); pickingDate = false },
            onDismiss = { pickingDate = false },
        )
    }
}

/** The date picker works in UTC midnights; convert to/from local-midnight epoch millis. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayPicker(initial: Long, onPicked: (Long) -> Unit, onDismiss: () -> Unit) {
    val zone = TimeZone.currentSystemDefault()
    val initialUtc = localDate(initial).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    val state = rememberDatePickerState(initialSelectedDateMillis = initialUtc)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { utc ->
                    val day = Instant.fromEpochMilliseconds(utc).toLocalDateTime(TimeZone.UTC).date
                    onPicked(day.atStartOfDayIn(zone).toEpochMilliseconds())
                } ?: onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}
