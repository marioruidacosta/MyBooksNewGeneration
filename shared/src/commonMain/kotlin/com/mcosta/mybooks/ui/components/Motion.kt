package com.mcosta.mybooks.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/** True when the phone's animations are off: every animation then shows its end state at once. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Whether the platform asks for reduced motion (Android: "Remove animations"; iOS: Reduce Motion). */
@Composable
expect fun rememberReduceMotion(): Boolean

/** Short transitions (a quarter of a second): nothing in the app waits on them. */
internal const val MOTION_MS = 250

/** [target] reached smoothly (a bar sliding to its new value), or at once with reduced motion. */
@Composable
fun animatedProgress(target: Float): Float {
    if (LocalReduceMotion.current) return target
    val shown by animateFloatAsState(target, tween(MOTION_MS), label = "progress")
    return shown
}

/** A number counting up from 0 to [value] when first shown (year in review), or [value] at once with reduced motion. */
@Composable
fun CountUp(value: Int, style: TextStyle, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val shown = remember(value) { Animatable(if (reduce) value.toFloat() else 0f) }
    LaunchedEffect(value, reduce) {
        if (reduce) shown.snapTo(value.toFloat()) else shown.animateTo(value.toFloat(), tween(800, easing = LinearEasing))
    }
    Text(shown.value.toInt().toString(), style = style, modifier = modifier, maxLines = 1)
}

/** A soft highlight sweeping across a placeholder while an image loads; a plain placeholder with reduced motion. */
fun Modifier.shimmer(): Modifier = composed {
    val base = MaterialTheme.colorScheme.surfaceVariant
    if (LocalReduceMotion.current) return@composed background(base)
    val sweep by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer-sweep",
    )
    background(base).drawWithContent {
        drawContent()
        val x = size.width * sweep
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent),
                start = Offset(x - size.width / 2, 0f),
                end = Offset(x + size.width / 2, size.height),
            ),
        )
    }
}
