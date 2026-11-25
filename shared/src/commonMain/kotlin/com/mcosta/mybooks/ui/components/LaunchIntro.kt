package com.mcosta.mybooks.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val LAUNCH_INTRO_TAG = "launch-intro"

/** Whether the intro has played since the app started: kept as long as the app runs, not across restarts. */
object LaunchIntroShown {
    var value = false
}

private const val WORD = "MyBooks"
private const val LETTER_MS = 560
private const val LETTER_STAGGER_MS = 85L
private const val SHELF_DELAY_MS = 680L
private const val SHELF_MS = 520
/** The whole intro before it fades: the letters and the shelf line, then a moment to read it. */
private const val SHOW_MS = 1400L
private const val BRIEF_MS = 500L
private const val FADE_MS = 350

/**
 * Opening the app, as on the website: the wordmark's letters rise in one after another, a shelf line draws under them,
 * then it all fades into the app and [onDone] is called. A tap skips it; with animations off the wordmark just shows
 * briefly. It says nothing to screen readers that the app doesn't.
 */
@Composable
fun LaunchIntro(reduceMotion: Boolean, onDone: () -> Unit) {
    val letters = remember { WORD.map { Animatable(if (reduceMotion) 1f else 0f) } }
    val shelf = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val shown = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }
    val leave = {
        if (!leaving) {
            leaving = true
            scope.launch {
                shown.animateTo(0f, tween(FADE_MS))
                onDone()
            }
        }
    }
    LaunchedEffect(Unit) {
        if (reduceMotion) {
            delay(BRIEF_MS)
        } else {
            coroutineScope {
                letters.forEachIndexed { i, letter ->
                    launch {
                        delay(i * LETTER_STAGGER_MS)
                        letter.animateTo(1f, tween(LETTER_MS, easing = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)))
                    }
                }
                launch {
                    delay(SHELF_DELAY_MS)
                    shelf.animateTo(1f, tween(SHELF_MS, easing = FastOutSlowInEasing))
                }
                launch { delay(SHOW_MS) }
            }
        }
        leave()
    }
    val color = MaterialTheme.colorScheme.tertiary
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = shown.value }
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) { detectTapGestures { leave() } }
            .clearAndSetSemantics { testTag = LAUNCH_INTRO_TAG },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(IntrinsicSize.Max)) {
            Row {
                WORD.forEachIndexed { i, c ->
                    Text(
                        c.toString(),
                        color = color,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 52.sp,
                        modifier = Modifier.graphicsLayer {
                            val p = letters[i].value
                            alpha = p
                            translationY = (1f - p) * size.height * 0.45f
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().height(3.dp)
                    .graphicsLayer {
                        scaleX = shelf.value
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .background(color, RoundedCornerShape(2.dp)),
            )
        }
    }
}
