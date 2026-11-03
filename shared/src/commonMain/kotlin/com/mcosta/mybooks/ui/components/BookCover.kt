package com.mcosta.mybooks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.mcosta.mybooks.ui.LocalCoverStore

/**
 * Book cover image (web URL or a `cover:` photo). Without one, or while it loads or when it fails, a cover drawn
 * from [title] and [authors] (a book icon when the title isn't known).
 */
@Composable
fun BookCover(url: String?, modifier: Modifier = Modifier, title: String? = null, authors: String = "") {
    val placeholder: @Composable () -> Unit = if (title != null) {
        { GeneratedCover(title, authors, Modifier.fillMaxSize()) }
    } else {
        {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp),
                )
            }
        }
    }
    Box(modifier.clip(RoundedCornerShape(4.dp))) {
        if (url.isNullOrBlank()) {
            placeholder()
        } else {
            SubcomposeAsyncImage(
                model = LocalCoverStore.current.imageModel(url),
                contentDescription = "Cover",
                contentScale = ContentScale.Crop,
                loading = { placeholder() },
                error = { placeholder() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
