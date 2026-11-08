package com.mcosta.mybooks.ui.social

import com.mcosta.mybooks.social.model.ActivityKind
import com.mcosta.mybooks.social.model.FeedEntry
import com.mcosta.mybooks.social.model.Person
import kotlin.time.Duration.Companion.hours

/** Books added this close together (each to the previous) by the same person make one card. */
const val GROUP_WINDOW_HOURS = 24

/** What the Community list shows: one update, or several books someone added at once. */
sealed interface FeedItem {
    val key: String

    data class Single(val entry: FeedEntry) : FeedItem {
        override val key: String get() = "e:${entry.id}"
    }

    data class Group(val actor: Person, val kind: ActivityKind, val entries: List<FeedEntry>) : FeedItem {
        override val key: String get() = "g:${entries.first().id}"
    }
}

private val GROUPABLE = setOf(ActivityKind.WANT_TO_READ, ActivityKind.WANT_TO_BUY)

/**
 * The feed's entries (newest first) as cards: consecutive "wants to read" (or "wants to buy") entries by the same
 * person, each within [GROUP_WINDOW_HOURS] of the previous one, become one [FeedItem.Group]; everything else, and a
 * run of one, stays a [FeedItem.Single]. Run on the whole loaded list, so a run split by a page boundary joins up.
 */
fun groupFeed(entries: List<FeedEntry>): List<FeedItem> {
    val items = mutableListOf<FeedItem>()
    var run = mutableListOf<FeedEntry>()

    fun flush() {
        when (run.size) {
            0 -> Unit
            1 -> items += FeedItem.Single(run.single())
            else -> items += FeedItem.Group(run.first().actor, run.first().kind, run)
        }
        run = mutableListOf()
    }

    for (entry in entries) {
        val last = run.lastOrNull()
        val joins = last != null && entry.kind in GROUPABLE && entry.kind == last.kind && entry.actor.id == last.actor.id &&
            last.createdAt - entry.createdAt <= GROUP_WINDOW_HOURS.hours
        if (!joins) flush()
        if (entry.kind in GROUPABLE) run += entry else items += FeedItem.Single(entry)
    }
    flush()
    return items
}
