package com.mcosta.mybooks.ui.components

import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.ui.library.label
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.lerp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag

/** Reading / To read / Read / To buy segmented buttons. */
@Composable
fun StatusSelector(status: ReadingStatus, onChange: (ReadingStatus) -> Unit, modifier: Modifier = Modifier) {
    val options = ReadingStatus.entries
    // Only a tap glides the highlight: a status that arrives with the book (opening Edit) just shows.
    var tapped by remember { mutableStateOf(false) }
    SingleChoiceSegmentedButtonRow(modifier) {
        options.forEachIndexed { i, option ->
            // The highlight glides to the new choice: each segment's colour follows its own 0..1 selection.
            val gliding = selectionProgress(status == option)
            val selected = if (tapped) gliding else if (status == option) 1f else 0f
            val active = MaterialTheme.colorScheme.secondaryContainer
            val inactive = MaterialTheme.colorScheme.surface
            SegmentedButton(
                selected = status == option,
                onClick = { tapped = true; onChange(option) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                modifier = Modifier.testTag("status-${option.name}").semantics { this[SelectionWeight] = selected },
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = lerp(inactive, active, selected),
                    inactiveContainerColor = lerp(inactive, active, selected),
                ),
                label = { Text(option.label()) },
            )
        }
    }
}
