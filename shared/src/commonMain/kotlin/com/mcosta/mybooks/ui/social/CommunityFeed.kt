package com.mcosta.mybooks.ui.social

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mcosta.mybooks.social.model.FeedBook
import com.mcosta.mybooks.social.model.FeedEntry
import com.mcosta.mybooks.social.model.FeedScope

/** What the Community list can do; the defaults do nothing (previews and screenshots). */
data class FeedCallbacks(
    val onPerson: (String) -> Unit = {},
    val onBook: (FeedBook) -> Unit = {},
    val onWantToRead: (FeedBook) -> Unit = {},
    val onWantToBuy: (FeedBook) -> Unit = {},
    val onOpenMine: (FeedBook) -> Unit = {},
    val onLike: (Long) -> Unit = {},
    val onLikers: (Long) -> Unit = {},
    val onReport: (FeedEntry) -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onSeeAllReading: () -> Unit = {},
)

/**
 * The Community list: on the Friends tab "Friends are reading" first, then the cards (books added at once grouped),
 * loading more at the end.
 */
@Composable
fun CommunityFeed(state: ActivityState, callbacks: FeedCallbacks, modifier: Modifier = Modifier) {
    val items = state.items
    LazyColumn(modifier.fillMaxSize()) {
        if (state.scope == FeedScope.FRIENDS && state.reading.isNotEmpty()) {
            item(key = "reading", contentType = "reading") {
                FriendsReadingRow(state.reading, onBook = callbacks.onBook, onSeeAll = callbacks.onSeeAllReading)
                HorizontalDivider()
            }
        }
        itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
            if (index == items.lastIndex) LaunchedEffect(item.key) { callbacks.onLoadMore() }
            when (item) {
                is FeedItem.Single -> {
                    val entry = item.entry
                    val mine = entry.actor.id == state.myId
                    FeedEntryCard(
                        entry = entry,
                        onPerson = { callbacks.onPerson(entry.actor.id) },
                        onBook = { callbacks.onBook(entry.book) },
                        onLike = if (mine) null else ({ callbacks.onLike(entry.id) }),
                        onLikers = { callbacks.onLikers(entry.id) },
                        onReport = if (mine) null else ({ callbacks.onReport(entry) }),
                        shelf = state.shelves[entry.book.shareId],
                        onWantToRead = if (mine) null else ({ callbacks.onWantToRead(entry.book) }),
                        onWantToBuy = if (mine) null else ({ callbacks.onWantToBuy(entry.book) }),
                        onOpenMine = { callbacks.onOpenMine(entry.book) },
                    )
                }
                is FeedItem.Group -> FeedGroupCard(item, onPerson = { callbacks.onPerson(item.actor.id) }, onBook = callbacks.onBook)
            }
            HorizontalDivider()
        }
        if (state.loadingMore) {
            item(key = "more") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}
