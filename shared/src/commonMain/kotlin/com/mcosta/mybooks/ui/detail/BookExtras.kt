package com.mcosta.mybooks.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mcosta.mybooks.data.authors.AuthorBook
import com.mcosta.mybooks.data.releases.StoreLink
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.social.model.BookReview
import com.mcosta.mybooks.social.model.BookScore
import com.mcosta.mybooks.ui.components.BookCover
import com.mcosta.mybooks.ui.components.RatingStars
import com.mcosta.mybooks.ui.formatDate
import com.mcosta.mybooks.ui.library.label
import com.mcosta.mybooks.ui.social.PersonAvatar
import com.mcosta.mybooks.ui.releases.tryOpen
import kotlin.math.roundToInt

/** The score bars' colour: the chart teal, validated for each surface (as the statistics chart). */
@Composable
private fun scoreBarColor(): Color = if (isSystemInDarkTheme()) Color(0xFF2BA89C) else Color(0xFF0A8F84)

private fun count(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

/**
 * Community reviews, as on Goodreads: the average score and how the scores spread, then readers' reviews (written
 * ones first), with "Show more".
 */
@Composable
fun CommunityReviews(
    state: CommunityState,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    onJoin: () -> Unit,
    onOpenPerson: (String) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (state) {
        CommunityState.Hidden -> Unit
        CommunityState.Loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        CommunityState.SignedOut -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("See what other readers scored and wrote about this book.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onJoin) { Text("Join the community") }
        }
        CommunityState.NoIsbn -> Text(
            "Community reviews need the book’s ISBN (edit the book to add it).",
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )
        is CommunityState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.message, style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text("Retry") }
        }
        is CommunityState.Loaded -> {
            if (state.score.ratings == 0 && state.reviews.isEmpty()) {
                Text("No community reviews yet.", style = MaterialTheme.typography.bodyMedium, color = muted)
                return
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ScoreSummary(state.score)
                for (review in state.reviews) {
                    HorizontalDivider()
                    ReviewItem(review, onOpenPerson)
                }
                if (state.canLoadMore) {
                    if (state.loadingMore) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = onMore) { Text("Show more reviews") }
                    }
                }
            }
        }
    }
}

/** "4.3 ★★★★☆ · 12 ratings · 5 reviews", then one bar per score, 5 to 1, each with its count. */
@Composable
private fun ScoreSummary(score: BookScore) {
    val average = score.average
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (average != null) {
            Text(((average * 10).roundToInt() / 10.0).toString(), style = MaterialTheme.typography.displaySmall)
        }
        Column {
            if (average != null) RatingStars(average.roundToInt(), onRate = null, size = 20.dp)
            Text(
                "${count(score.ratings, "rating", "ratings")} · ${count(score.reviews, "review", "reviews")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (score.ratings == 0) return
    val bar = scoreBarColor()
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        score.perScore.forEachIndexed { i, n ->
            val stars = 5 - i
            val share = n.toFloat() / score.ratings
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics { contentDescription = "$stars stars: $n" },
            ) {
                Text("$stars ★", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(32.dp))
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(track)) {
                    if (n > 0) Box(Modifier.fillMaxHeight().fillMaxWidth(share).clip(RoundedCornerShape(4.dp)).background(bar))
                }
                Text(
                    "$n",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(36.dp).padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ReviewItem(review: BookReview, onOpenPerson: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.clickable { onOpenPerson(review.person.id) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PersonAvatar(review.person.avatarUrl, size = 36.dp)
            Column {
                Text(review.person.displayName, style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    review.rating?.let { RatingStars(it, onRate = null, size = 14.dp) }
                    Text(
                        "  ${formatDate(review.updatedAt.toEpochMilliseconds())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (review.review.isNotBlank()) {
            Text(
                review.review,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
    }
}

/** "More by …": a row of the author's other books; the library's open, the others offer to be added. */
@Composable
fun MoreByRow(state: MoreByState, onOpenBook: (Long) -> Unit, onShow: (AuthorBook) -> Unit, edgePadding: Dp = 16.dp) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("More by ${state.author}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = edgePadding))
        when {
            state.loading -> CircularProgressIndicator(Modifier.padding(horizontal = edgePadding).size(24.dp), strokeWidth = 2.dp)
            state.books.isEmpty() -> Text(
                "No other books found.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = edgePadding),
            )
            else -> LazyRow(contentPadding = PaddingValues(horizontal = edgePadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.books) { more ->
                    val book = more.book
                    Column(
                        Modifier.width(96.dp).clickable { more.inLibrary?.let { onOpenBook(it.id) } ?: onShow(book) },
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Surface(shape = RoundedCornerShape(4.dp), shadowElevation = 3.dp, modifier = Modifier.width(96.dp).height(144.dp)) {
                            BookCover(book.coverUrl, Modifier.fillMaxSize(), title = book.title, authors = book.authors)
                        }
                        Text(book.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val note = more.inLibrary?.status?.label() ?: book.year?.toString()
                        note?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (more.inLibrary != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}


/** "Where to buy": a button per shop, each opening its search for the book (by ISBN, or title and author). */
@Composable
fun WhereToBuy(stores: List<StoreLink>, byIsbn: Boolean, onCantOpen: () -> Unit) {
    val uri = LocalUriHandler.current
    SectionCard("Where to buy") {
        Text(
            if (byIsbn) "Opens each shop’s search for this book’s ISBN."
            else "This book has no ISBN: each shop is searched by title and author.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (store in stores) {
            OutlinedButton(onClick = { if (!uri.tryOpen(store.url)) onCantOpen() }, modifier = Modifier.fillMaxWidth()) {
                Text(store.name, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
            }
        }
    }
}
