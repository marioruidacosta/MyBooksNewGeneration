package com.mcosta.mybooks.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** A book's cover picture (if any) and what a generated cover needs. */
data class CoverRef(val url: String?, val title: String, val authors: String)

/** Covers overlapping left to right, the first on top; a placeholder cover when there are none. */
@Composable
fun CoverFan(covers: List<CoverRef>, modifier: Modifier = Modifier, coverWidth: Dp = 44.dp, step: Dp = 24.dp) {
    val shown: List<CoverRef?> = covers.ifEmpty { listOf(null) }
    Box(modifier.width(coverWidth + step * (shown.size - 1)).height(coverWidth * 1.5f)) {
        shown.forEachIndexed { i, cover ->
            Surface(
                shape = RoundedCornerShape(4.dp),
                shadowElevation = 3.dp,
                modifier = Modifier.offset(x = step * i).zIndex((shown.size - i).toFloat()).width(coverWidth).height(coverWidth * 1.5f),
            ) {
                BookCover(cover?.url, Modifier.fillMaxSize(), title = cover?.title, authors = cover?.authors.orEmpty())
            }
        }
    }
}
