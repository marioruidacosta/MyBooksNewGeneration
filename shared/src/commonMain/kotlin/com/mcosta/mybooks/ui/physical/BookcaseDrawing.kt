package com.mcosta.mybooks.ui.physical

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.physical.Bookcase
import com.mcosta.mybooks.physical.shelfLabel
import com.mcosta.mybooks.ui.components.coverColorArgb

private val Wood = Color(0xFF5B3A22)
private val WoodDark = Color(0xFF3A2517)
private val ShelfBack = Color(0xFF1C120B)
private val Plank = Color(0xFF84583A)
private val Brass = Color(0xFFD9A54A)
private val Cream = Color(0xFFF7F1E6)

/** Below this a shelf is a thumbnail: thin spines with no titles. */
private val ThumbnailHeight = 24.dp
private val MoreWidth = 22.dp

/** A bookcase from the front: columns side by side, each shelf with its books as spines, left to right. */
@Composable
fun BookcaseDrawing(
    bookcase: Bookcase,
    booksOn: (String) -> List<Book>,
    modifier: Modifier = Modifier,
    labels: Boolean = false,
    shelfHeight: Dp = 56.dp,
    highlightShelf: String? = null,
    highlightBook: Long? = null,
    onShelf: ((String) -> Unit)? = null,
) {
    val rows = bookcase.columns.maxOf { it.shelves.size }
    val thumbnail = shelfHeight < ThumbnailHeight
    val gap = if (thumbnail) 1.dp else 3.dp
    Row(
        modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Brush.verticalGradient(listOf(Wood, WoodDark)))
            .padding(gap + 1.dp)
            .height(shelfHeight * rows + gap * (rows - 1)),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        bookcase.columns.forEachIndexed { c, column ->
            // A column with fewer shelves has taller ones, as in the furniture.
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                column.shelves.forEachIndexed { s, shelfId ->
                    ShelfDrawing(
                        label = shelfLabel(c, s),
                        books = booksOn(shelfId),
                        thumbnail = thumbnail,
                        showLabel = labels,
                        highlighted = shelfId == highlightShelf,
                        highlightBook = highlightBook,
                        onClick = onShelf?.let { open -> { open(shelfId) } },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun spineWidth(book: Book, thumbnail: Boolean): Dp =
    if (thumbnail) 3.dp else (7 + (book.title.hashCode() and Int.MAX_VALUE) % 5).dp

private fun spineHeight(book: Book): Float = 0.72f + ((book.title.hashCode() and Int.MAX_VALUE) % 26) / 100f

@Composable
private fun ShelfDrawing(
    label: String,
    books: List<Book>,
    thumbnail: Boolean,
    showLabel: Boolean,
    highlighted: Boolean,
    highlightBook: Long?,
    onClick: (() -> Unit)?,
    modifier: Modifier,
) {
    BoxWithConstraints(
        modifier
            .background(ShelfBack)
            .then(if (highlighted) Modifier.border(2.dp, Brass) else Modifier),
    ) {
        val available = maxWidth - 4.dp
        val widths = books.map { spineWidth(it, thumbnail) + 1.dp }
        val total = widths.fold(0.dp) { sum, w -> sum + w }
        val room = if (total <= available) available else available - MoreWidth
        var used = 0.dp
        var fit = 0
        for (w in widths) {
            if (used + w > room) break
            used += w
            fit++
        }
        // The book being pointed out is always drawn, even past the edge of a full shelf.
        val highlightedIndex = books.indexOfFirst { it.id == highlightBook }
        val shown = if (highlightedIndex >= fit && fit > 0) books.take(fit - 1) + books[highlightedIndex] else books.take(fit)
        val hidden = books.size - shown.size
        val count = if (books.size == 1) "1 book" else "${books.size} books"
        val description = "Shelf $label, $count" + if (hidden > 0) ", $hidden not shown" else ""

        Box(
            Modifier
                .fillMaxSize()
                .clearAndSetSemantics {
                    if (showLabel || onClick != null) contentDescription = description
                    if (onClick != null) {
                        role = Role.Button
                        onClick { onClick(); true }
                    }
                }
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                shown.forEach { Spine(it, thumbnail, it.id == highlightBook) }
            }
            if (hidden > 0 && !thumbnail) {
                Text(
                    "+$hidden",
                    color = Cream,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 2.dp),
                )
            }
            if (showLabel) {
                Text(
                    label,
                    color = Cream,
                    fontSize = 9.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun Spine(book: Book, thumbnail: Boolean, highlighted: Boolean) {
    Box(
        Modifier
            .width(spineWidth(book, thumbnail))
            .fillMaxHeight(spineHeight(book))
            // A lent book's place is kept, and it shows as away.
            .alpha(if (book.lentTo != null && !highlighted) 0.4f else 1f)
            .clip(RoundedCornerShape(topStart = 1.dp, topEnd = 1.dp))
            .background(if (highlighted) Brass else Color(coverColorArgb(book.title))),
        contentAlignment = Alignment.Center,
    ) {
        if (!thumbnail) {
            Text(
                book.title,
                color = if (highlighted) Color(0xFF1B1305) else Cream,
                fontSize = 6.sp,
                lineHeight = 6.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                softWrap = false,
                modifier = Modifier.sideways(),
            )
        }
    }
}

/** Text running up the spine: measured with width and height swapped, then turned. */
private fun Modifier.sideways(): Modifier = this
    .layout { measurable, constraints ->
        val placeable = measurable.measure(Constraints(maxWidth = constraints.maxHeight, maxHeight = constraints.maxWidth))
        layout(placeable.height, placeable.width) {
            placeable.place((placeable.height - placeable.width) / 2, (placeable.width - placeable.height) / 2)
        }
    }
    .rotate(90f)
