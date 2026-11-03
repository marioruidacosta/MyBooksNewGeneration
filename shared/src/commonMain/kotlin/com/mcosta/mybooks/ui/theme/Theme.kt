package com.mcosta.mybooks.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/** "Book" colours: cream paper and dark ink, with a deep teal accent and leather brown. */
private val BookLight = lightColorScheme(
    primary = Color(0xFF1F5F5B), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBFE5DF), onPrimaryContainer = Color(0xFF00201D),
    secondary = Color(0xFF8A5A44), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3DDD2), onSecondaryContainer = Color(0xFF33180B),
    tertiary = Color(0xFF6B5E2E), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF4E2A6), onTertiaryContainer = Color(0xFF221B00),
    background = Color(0xFFFBF7F0), onBackground = Color(0xFF1E1B16),
    surface = Color(0xFFFBF7F0), onSurface = Color(0xFF1E1B16),
    surfaceVariant = Color(0xFFEDE5D8), onSurfaceVariant = Color(0xFF4E473D),
    outline = Color(0xFF80776B), outlineVariant = Color(0xFFD2C8B9),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF6F1E8),
    surfaceContainer = Color(0xFFF1EBE1), surfaceContainerHigh = Color(0xFFEBE5DA), surfaceContainerHighest = Color(0xFFE5DFD4),
)

private val BookDark = darkColorScheme(
    primary = Color(0xFF8FD3CB), onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF00504B), onPrimaryContainer = Color(0xFFAFF0E7),
    secondary = Color(0xFFE7BEA9), onSecondary = Color(0xFF4C2A19),
    secondaryContainer = Color(0xFF66402E), onSecondaryContainer = Color(0xFFFFDBCB),
    tertiary = Color(0xFFD7C68C), onTertiary = Color(0xFF3A3000),
    tertiaryContainer = Color(0xFF524618), onTertiaryContainer = Color(0xFFF4E2A6),
    background = Color(0xFF16130F), onBackground = Color(0xFFEAE1D6),
    surface = Color(0xFF16130F), onSurface = Color(0xFFEAE1D6),
    surfaceVariant = Color(0xFF4E473D), onSurfaceVariant = Color(0xFFD2C8B9),
    outline = Color(0xFF9B9184), outlineVariant = Color(0xFF4E473D),
    surfaceContainerLowest = Color(0xFF110E0A), surfaceContainerLow = Color(0xFF1E1B16),
    surfaceContainer = Color(0xFF221F1A), surfaceContainerHigh = Color(0xFF2D2924), surfaceContainerHighest = Color(0xFF38342E),
)

/** Headings and titles in the phone's serif typeface (no font to download); body text stays sans-serif. */
private val BookTypography = Typography().run {
    fun TextStyle.serif() = copy(fontFamily = FontFamily.Serif)
    copy(
        displayLarge = displayLarge.serif(), displayMedium = displayMedium.serif(), displaySmall = displaySmall.serif(),
        headlineLarge = headlineLarge.serif(), headlineMedium = headlineMedium.serif(), headlineSmall = headlineSmall.serif(),
        titleLarge = titleLarge.serif(), titleMedium = titleMedium.serif(),
    )
}

/** The platform's own colours (Android 12+ wallpaper colours), or null where there are none. */
@Composable
expect fun platformColorScheme(dark: Boolean): ColorScheme?

/** Whether this phone can take the app's colours from the wallpaper (Android 12 and later). */
expect val supportsWallpaperColors: Boolean

/** Material 3 theme: the book colours, or the wallpaper's when [followWallpaper] and the phone supports it. */
@Composable
fun MyBooksTheme(followWallpaper: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = (if (followWallpaper) platformColorScheme(dark) else null) ?: if (dark) BookDark else BookLight
    MaterialTheme(colorScheme = colors, typography = BookTypography, content = content)
}
