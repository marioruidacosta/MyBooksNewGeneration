package com.mcosta.mybooks.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.graphics.Color
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

/** "My", then "Books": when each starts to rise, and for how long. */
private const val WORD_MS = 500
private const val BOOKS_DELAY_MS = 450L
private const val SHELF_DELAY_MS = 900L
private const val SHELF_MS = 480
/** The whole intro before it fades: both words and the shelf line, then a moment to read it. */
private const val SHOW_MS = 1500L
private const val BRIEF_MS = 500L
private const val FADE_MS = 350

/**
 * The intro's own colours, the same whatever the app's theme (the wallpaper's colours included): MyBooks gold on the
 * website's Night green, or on its paper in light mode. The launch screen before it has the same background
 * (launch_background in the Android app's resources), so starting the app doesn't flash.
 */
private class IntroColors(val background: Color, val word: Color, val shelf: Color)

private val NIGHT = IntroColors(Color(0xFF0F1C18), Color(0xFFF3DCA6), Color(0xFFD9A54A))
private val DAY = IntroColors(Color(0xFFFBF7F0), Color(0xFF6E5414), Color(0xFFA07C1C))

/**
 * Opening the app, as on the website: "My" rises in, then "Books", a shelf line draws under them, then it all fades
 * into the app and [onDone] is called. A tap skips it; with animations off the wordmark just shows briefly. It says
 * nothing to screen readers that the app doesn't.
 */
@Composable
fun LaunchIntro(reduceMotion: Boolean, onDone: () -> Unit) {
    val words = remember { listOf("My", "Books").map { it to Animatable(if (reduceMotion) 1f else 0f) } }
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
                words.forEachIndexed { i, (_, word) ->
                    launch {
                        delay(i * BOOKS_DELAY_MS)
                        word.animateTo(1f, tween(WORD_MS, easing = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)))
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
    val colors = if (isSystemInDarkTheme()) NIGHT else DAY
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = shown.value }
            .background(colors.background)
            .pointerInput(Unit) { detectTapGestures { leave() } }
            .clearAndSetSemantics { testTag = LAUNCH_INTRO_TAG },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(IntrinsicSize.Max)) {
            Row {
                for ((text, progress) in words) {
                    Text(
                        text,
                        color = colors.word,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 52.sp,
                        modifier = Modifier.graphicsLayer {
                            val p = progress.value
                            alpha = p
                            translationY = (1f - p) * size.height * 0.3f
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
                    .background(colors.shelf, RoundedCornerShape(2.dp)),
            )
        }
    }
}
