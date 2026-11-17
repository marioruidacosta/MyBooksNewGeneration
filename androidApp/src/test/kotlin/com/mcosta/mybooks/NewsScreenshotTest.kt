package com.mcosta.mybooks

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.ui.news.HomeNewsCard
import com.mcosta.mybooks.ui.news.NewsScreen
import com.mcosta.mybooks.ui.theme.MyBooksTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** Book news drawn with the real sample feed, light and dark, saved as images (like the main screens' ScreenshotTest). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class NewsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val out = File(System.getenv("NEWS_SHOTS") ?: "build/screenshots").apply { mkdirs() }

    private fun shoot(name: String, dark: Boolean, ready: () -> Boolean, act: () -> Unit = {}, content: @androidx.compose.runtime.Composable () -> Unit) {
        if (dark) RuntimeEnvironment.setQualifiers("+night")
        // Inspection mode: pictures are not downloaded, so the thumbnails show as plain blocks.
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { MyBooksTheme { content() } } }
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); ready() }
        act()
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name-${if (dark) "dark" else "light"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun stories(dark: Boolean) {
        val vm = NewsHarness(context).viewModel()
        shoot("news-stories", dark, ready = { vm.state.value.items.isNotEmpty() }) { NewsScreen(vm, onBack = {}) }
    }

    private fun calendar(dark: Boolean) {
        val vm = NewsHarness(context).viewModel()
        shoot("news-calendar", dark, ready = { !vm.state.value.eventsLoading }, act = { compose.onNodeWithText("Book calendar").performClick() }) {
            NewsScreen(vm, onBack = {})
        }
    }

    private fun homeCard(dark: Boolean) {
        val vm = NewsHarness(context).viewModel()
        shoot("news-home-card", dark, ready = { vm.state.value.items.isNotEmpty() }) {
            Surface(Modifier.fillMaxSize()) { Column { HomeNewsCard(vm.state.collectAsState().value, onOpenStory = {}, onSeeAll = {}) } }
        }
    }

    @Test fun storiesLight() = stories(dark = false)
    @Test fun storiesDark() = stories(dark = true)
    @Test fun calendarLight() = calendar(dark = false)
    @Test fun calendarDark() = calendar(dark = true)
    @Test fun homeCardLight() = homeCard(dark = false)
    @Test fun homeCardDark() = homeCard(dark = true)
}
