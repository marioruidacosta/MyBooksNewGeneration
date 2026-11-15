package com.mcosta.mybooks.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.semantics

/**
 * 1–5 star rating. With [onRate] the stars are tappable: tapping star n rates n, tapping the
 * current rating clears it (null). With [onRate] null the stars are display-only.
 */
@Composable
fun RatingStars(rating: Int?, onRate: ((Int?) -> Unit)?, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    // Only a tap pops the stars: a score that arrives with the book (opening Edit) just shows.
    var taps by remember { mutableIntStateOf(0) }
    Row(modifier) {
        for (n in 1..5) {
            val filled = rating != null && n <= rating
            // A new score pops the stars up to it, one after another.
            val pop = rememberPop(taps, delayMs = (n - 1) * 40)
            val scale = if (filled) pop else 1f
            val icon = @Composable {
                Icon(
                    if (filled) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (onRate != null) "Rate $n" else null,
                    tint = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }.semantics { this[StarScale] = scale },
                )
            }
            if (onRate != null) {
                IconButton(onClick = { taps++; onRate(if (rating == n) null else n) }) { icon() }
            } else {
                icon()
            }
        }
    }
}
