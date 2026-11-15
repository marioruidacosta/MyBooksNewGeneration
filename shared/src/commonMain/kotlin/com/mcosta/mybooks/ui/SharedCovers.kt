@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.mcosta.mybooks.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mcosta.mybooks.ui.components.LocalReduceMotion
import com.mcosta.mybooks.ui.components.MOTION_MS

/** The shared-transition area around the screens (a book's cover moves from a list into its page). */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The animation of the screen being shown, which the shared cover follows. */
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** For tests: the key a cover shares with the same book's cover on the next screen. */
val ShareKey = SemanticsPropertyKey<String>("ShareKey")

/** Lets covers inside [content] move between screens. */
@Composable
fun SharedCovers(content: @Composable () -> Unit) {
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) { content() }
    }
}

/** A screen of the app's NavHost whose covers can move to and from the next screen. */
fun NavGraphBuilder.screen(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route, arguments) { entry ->
        CompositionLocalProvider(LocalNavAnimatedScope provides this) { content(entry) }
    }
}

/**
 * The cover for [key] moves to the cover with the same key on the next screen (a list's cover growing into the book's
 * page). One key per book per screen. Nothing when there is no key, outside the screens, or with reduced motion.
 */
@Composable
fun Modifier.sharedCover(key: String?): Modifier {
    if (key == null) return this
    val tagged = semantics { this[ShareKey] = key }
    val transition = LocalSharedTransitionScope.current
    val screen = LocalNavAnimatedScope.current
    if (transition == null || screen == null || LocalReduceMotion.current) return tagged
    return with(transition) {
        tagged.sharedElement(
            rememberSharedContentState(key),
            animatedVisibilityScope = screen,
            boundsTransform = { _, _ -> tween(MOTION_MS) },
        )
    }
}
