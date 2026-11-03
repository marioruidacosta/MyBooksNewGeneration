package com.mcosta.mybooks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.mcosta.mybooks.domain.searchKey

/** Muted cloth-binding tones; light text reads on all of them. */
internal val COVER_COLORS = listOf(
    Color(0xFF33506B), Color(0xFF6B3A3A), Color(0xFF2F5D50), Color(0xFF5B4A6E), Color(0xFF7A5C2E),
    Color(0xFF3E4A5C), Color(0xFF6E4B5A), Color(0xFF4A5D3A), Color(0xFF7B4A2F), Color(0xFF2E4E5E),
)

/**
 * The colour of a title's generated cover: the same on every device and in every version (a fixed hash of the
 * title ignoring case and accents, as String.hashCode isn't promised to match across platforms).
 */
fun coverColorIndex(title: String): Int {
    var hash = 0
    for (c in searchKey(title)) hash = (hash * 31 + c.code) and 0x7fffffff
    return hash % COVER_COLORS.size
}

/** A cover drawn for a book without one: its colour, a darker spine, the title in serif and the author below. */
@Composable
fun GeneratedCover(title: String, authors: String, modifier: Modifier = Modifier) {
    val color = COVER_COLORS[coverColorIndex(title)]
    BoxWithConstraints(modifier.background(color)) {
        val w = maxWidth
        Box(Modifier.width(w * 0.07f).fillMaxHeight().background(Color.Black.copy(alpha = 0.22f)))
        Column(Modifier.fillMaxSize().padding(start = w * 0.14f, end = w * 0.08f, top = w * 0.1f, bottom = w * 0.08f)) {
            Text(
                title,
                color = Color(0xFFF7F1E6),
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = (w.value * 0.13f).sp,
                lineHeight = (w.value * 0.155f).sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            val author = authors.substringBefore(',').trim()
            if (author.isNotEmpty()) {
                Text(
                    author,
                    color = Color(0xFFF7F1E6).copy(alpha = 0.85f),
                    fontSize = (w.value * 0.085f).sp,
                    lineHeight = (w.value * 0.1f).sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
