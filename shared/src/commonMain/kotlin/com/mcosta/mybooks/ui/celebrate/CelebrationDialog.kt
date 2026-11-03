package com.mcosta.mybooks.ui.celebrate

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mcosta.mybooks.ui.components.BookCover
import com.mcosta.mybooks.ui.components.RatingStars

/** "Finished!": the book's cover popping in, what it means for the year, and its score. */
@Composable
fun CelebrationDialog(celebration: Celebration, onRate: (Int) -> Unit, onSetGoal: () -> Unit, onDismiss: () -> Unit) {
    val book = celebration.book
    var shown by remember(book.id) { mutableStateOf(false) }
    LaunchedEffect(book.id) { shown = true }
    val scale by animateFloatAsState(if (shown) 1f else 0.6f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Celebration, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Finished!") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(6.dp), shadowElevation = 8.dp, modifier = Modifier.scale(scale)) {
                    BookCover(book.coverUrl, Modifier.width(96.dp).height(144.dp), title = book.title, authors = book.authors)
                }
                Text(book.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(celebration.line(), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                celebration.challenge?.let { LinearProgressIndicator(progress = { it.percent / 100f }, modifier = Modifier.fillMaxWidth()) }
                Text("Your score", style = MaterialTheme.typography.labelLarge)
                RatingStars(book.rating, onRate = { it?.let(onRate) }, size = 32.dp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = if (celebration.challenge == null) {
            { TextButton(onClick = onSetGoal) { Text("Set a goal") } }
        } else {
            null
        },
    )
}
