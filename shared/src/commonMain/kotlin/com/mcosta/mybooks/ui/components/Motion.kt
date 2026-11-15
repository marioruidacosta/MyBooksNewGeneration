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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import kotlin.random.Random
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.ui.semantics.SemanticsPropertyKey

/** True when the phone's animations are off: every animation then shows its end state at once. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Whether the platform asks for reduced motion (Android: "Remove animations"; iOS: Reduce Motion). */
@Composable
expect fun rememberReduceMotion(): Boolean

/** Short transitions (a quarter of a second): nothing in the app waits on them. */
internal const val MOTION_MS = 250

/** How long screens fade into each other: in step with a moving cover; none with reduced motion. */
fun screenFadeMillis(reduce: Boolean): Int = if (reduce) 0 else MOTION_MS

/** For tests: a star's current scale (1 at rest, more while it pops). */
val StarScale = SemanticsPropertyKey<Float>("StarScale")

/** For tests: how selected a status segment looks, 0 to 1. */
val SelectionWeight = SemanticsPropertyKey<Float>("SelectionWeight")

/** [target] reached smoothly (a bar sliding to its new value), or at once with reduced motion. */
@Composable
fun animatedProgress(target: Float): Float {
    if (LocalReduceMotion.current) return target
    val shown by animateFloatAsState(target, tween(MOTION_MS), label = "progress")
    return shown
}

/** A number counting up from 0 to [value] when first shown (year in review), or [value] at once with reduced motion. */
@Composable
fun CountUp(value: Int, style: TextStyle, modifier: Modifier = Modifier, format: (Int) -> String = { it.toString() }) {
    val reduce = LocalReduceMotion.current
    val shown = remember(value) { Animatable(if (reduce) value.toFloat() else 0f) }
    LaunchedEffect(value, reduce) {
        if (reduce) shown.snapTo(value.toFloat()) else shown.animateTo(value.toFloat(), tween(800, easing = LinearEasing))
    }
    Text(format(shown.value.toInt()), style = style, modifier = modifier, maxLines = 1)
}

/** How selected something looks, 0 to 1: glides when the choice changes (the status highlight), at once if reduced. */
@Composable
fun selectionProgress(selected: Boolean): Float {
    val target = if (selected) 1f else 0f
    if (LocalReduceMotion.current) return target
    val shown by animateFloatAsState(target, tween(MOTION_MS), label = "selection")
    return shown
}

/**
 * A quick "pop" each time [trigger] changes (a star when tapped): the scale rises to 1.25 and settles back to 1,
 * after [delayMs]. Always 1 on first show and with reduced motion.
 */
@Composable
fun rememberPop(trigger: Any?, delayMs: Int = 0): Float {
    val reduce = LocalReduceMotion.current
    val scale = remember { Animatable(1f) }
    val first = remember { mutableStateOf(true) }
    LaunchedEffect(trigger) {
        if (first.value) {
            first.value = false
            return@LaunchedEffect
        }
        if (reduce) return@LaunchedEffect
        scale.snapTo(1f)
        scale.animateTo(1.25f, tween(90, delayMillis = delayMs))
        scale.animateTo(1f, tween(180))
    }
    return scale.value
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

/** One piece of confetti: where it starts (fractions of the area), how it flies, and how it looks. */
private class Piece(val x: Float, val y: Float, val vx: Float, val vy: Float, val spin: Float, val color: Color, val wide: Boolean)

/**
 * A short burst of confetti over [modifier]'s area (finishing a book): pieces fly up and out, fall, spin and fade, and
 * the layer goes away after ~1.5 s. Nothing at all with reduced motion.
 */
@Composable
fun Confetti(modifier: Modifier = Modifier) {
    if (LocalReduceMotion.current) return
    val colors = listOf(
        MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary,
        Color(0xFFE9B949), Color(0xFFD9645B), Color(0xFF6FA8DC),
    )
    val pieces = remember {
        val random = Random(7)
        List(60) {
            Piece(
                x = 0.5f + (random.nextFloat() - 0.5f) * 0.2f,
                y = 0.35f,
                vx = (random.nextFloat() - 0.5f) * 1.3f,
                vy = -(0.5f + random.nextFloat() * 0.8f),
                spin = (random.nextFloat() - 0.5f) * 1080f,
                color = colors[it % colors.size],
                wide = random.nextBoolean(),
            )
        }
    }
    val time = remember { Animatable(0f) }
    val done = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        time.animateTo(1f, tween(CONFETTI_MS, easing = LinearEasing))
        done.value = true
    }
    if (done.value) return
    Canvas(modifier.testTag("confetti")) {
        val t = time.value
        val fade = if (t < 0.7f) 1f else (1f - (t - 0.7f) / 0.3f).coerceIn(0f, 1f)
        for (p in pieces) {
            val x = (p.x + p.vx * t) * size.width
            val y = (p.y + p.vy * t + 1.6f * t * t) * size.height
            val unit = 2.5.dp.toPx()
            val w = if (p.wide) 3 * unit else 2 * unit
            val h = 3.5f * unit
            rotate(p.spin * t, pivot = Offset(x, y)) {
                drawRect(p.color.copy(alpha = fade), topLeft = Offset(x - w / 2, y - h / 2), size = Size(w, h))
            }
        }
    }
}

internal const val CONFETTI_MS = 1500

/** A list row that slides into place when added and closes the gap when removed (none with reduced motion). */
@Composable
fun LazyItemScope.motionItem(): Modifier = if (LocalReduceMotion.current) Modifier else Modifier.animateItem()

/** The grid version of [motionItem]. */
@Composable
fun LazyGridItemScope.motionItem(): Modifier = if (LocalReduceMotion.current) Modifier else Modifier.animateItem()
