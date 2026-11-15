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

/** Reading / To read / Read / To buy segmented buttons. */
@Composable
fun StatusSelector(status: ReadingStatus, onChange: (ReadingStatus) -> Unit, modifier: Modifier = Modifier) {
    val options = ReadingStatus.entries
    SingleChoiceSegmentedButtonRow(modifier) {
        options.forEachIndexed { i, option ->
            // The highlight glides to the new choice: each segment's colour follows its own 0..1 selection.
            val selected = selectionProgress(status == option)
            val active = MaterialTheme.colorScheme.secondaryContainer
            val inactive = MaterialTheme.colorScheme.surface
            SegmentedButton(
                selected = status == option,
                onClick = { onChange(option) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = lerp(inactive, active, selected),
                    inactiveContainerColor = lerp(inactive, active, selected),
                ),
                label = { Text(option.label()) },
            )
        }
    }
}
