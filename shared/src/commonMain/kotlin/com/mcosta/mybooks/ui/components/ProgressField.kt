package com.mcosta.mybooks.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A small number field with round ends, the height of the buttons beside it (page or percentage of reading
 * progress); [hint] shows while it's empty.
 */
@Composable
fun ProgressField(value: String, onValueChange: (String) -> Unit, hint: String, isError: Boolean, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val outline = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
    val style = MaterialTheme.typography.bodyLarge
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(5)) },
        singleLine = true,
        textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.width(88.dp).height(40.dp),
        decorationBox = { field ->
            Box(Modifier.fillMaxSize().border(1.dp, outline, CircleShape).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                if (value.isEmpty()) {
                    Text(hint, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                field()
            }
        },
    )
}
