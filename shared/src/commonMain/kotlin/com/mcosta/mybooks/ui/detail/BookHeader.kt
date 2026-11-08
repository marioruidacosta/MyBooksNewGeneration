package com.mcosta.mybooks.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcosta.mybooks.ui.components.BookCover

/**
 * A large cover over a softly blurred copy of it, fading into the page (Android blurs from version 12; earlier
 * it's a shaded wash), then the title and authors. Swiping the cover shows the book's [details], then its author, in its
 * place (three dots show which). Used by the library, New in Portugal and catalogue book pages.
 */
@Composable
fun BookHeader(
    coverUrl: String?,
    title: String,
    authors: String,
    /** The second panel: label and value per line. */
    details: List<Pair<String, String>>,
    author: AuthorPanel?,
    /** Called when the author panel is first shown (its biography is fetched then). */
    onAuthorShown: () -> Unit,
    /** Opens one of the library's other books by the author. */
    onOpenBook: (Long) -> Unit,
    /** Shown as "Add a cover photo" when there is no cover; null hides it. */
    onAddPhoto: (() -> Unit)? = null,
) {
    val pages = rememberPagerState { 3 }
    // The author's biography is fetched only when their panel is first shown.
    LaunchedEffect(pages.currentPage) { if (pages.currentPage == 2) onAuthorShown() }
    Box(Modifier.fillMaxWidth()) {
        BookCover(coverUrl, Modifier.matchParentSize().blur(28.dp).alpha(0.5f), title = title, authors = authors)
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface.copy(alpha = 0.2f), MaterialTheme.colorScheme.surface)),
            ),
        )
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HorizontalPager(pages, modifier = Modifier.fillMaxWidth().height(COVER_HEIGHT)) { page ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (page == 0) {
                        Surface(shape = RoundedCornerShape(6.dp), shadowElevation = 10.dp) {
                            BookCover(coverUrl, Modifier.width(COVER_HEIGHT * 2 / 3).height(COVER_HEIGHT), title = title, authors = authors)
                        }
                    } else {
                        if (page == 1) DetailsPanel(details) else AuthorPanelView(author, onOpenBook)
                    }
                }
            }
            PageDots(current = pages.currentPage, count = pages.pageCount)
            // No cover found: photograph the book.
            if (coverUrl.isNullOrBlank() && onAddPhoto != null) TextButton(onClick = onAddPhoto) { Text("Add a cover photo") }
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            if (authors.isNotBlank()) Text(authors, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }
}

private val COVER_HEIGHT = 225.dp

/** The book's details in the cover's place, as plain text over the blurred backdrop: label and value per line. */
@Composable
private fun DetailsPanel(details: List<Pair<String, String>>) {
    Column(
        Modifier.fillMaxHeight().widthIn(max = 300.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        for ((label, value) in details) {
            Row {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(84.dp),
                )
                Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Which of the header's panels is showing. */
@Composable
private fun PageDots(current: Int, count: Int) {
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val color = if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        }
    }
}
