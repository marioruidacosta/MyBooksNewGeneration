package com.mcosta.mybooks.data.news

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Where the news job publishes `news.json` and `events.json` (GitHub Pages). */
const val NEWS_BASE_URL = "https://marioruidacosta.github.io/MyBooksNewGeneration/news/"

/** One story: a headline, a short plain-text summary (maybe empty) and a link to the original article. */
@Serializable
data class NewsItem(
    val id: String,
    val title: String,
    val summary: String = "",
    val url: String,
    val source: String,
    /** `pt` or `en`. */
    val lang: String,
    /** Epoch milliseconds. */
    val published: Long,
    val image: String? = null,
)

@Serializable
data class NewsSource(val id: String, val name: String, val lang: String = "", val ok: Boolean = true)

/** The published feed, with [items] newest first. */
@Serializable
data class NewsFeed(
    val generatedAt: Long = 0,
    val sources: List<NewsSource> = emptyList(),
    val items: List<NewsItem> = emptyList(),
) {
    /** The source's display name, or its id when it is not listed. */
    fun sourceName(id: String): String = sources.firstOrNull { it.id == id }?.name ?: id
}

/** A recurring book event from the hand-kept calendar: usually held in [month] (1 to 12). */
@Serializable
data class BookEvent(
    val name: String,
    val place: String = "",
    val month: Int,
    val kind: String = "",
    val lang: String = "",
    val url: String,
)

@Serializable
internal data class EventsJson(val events: List<BookEvent> = emptyList())

/** The language chips: All, Português, English. */
enum class NewsLanguage(val label: String, val code: String?) {
    ALL("All", null), PT("Português", "pt"), EN("English", "en")
}

fun List<NewsItem>.inLanguage(language: NewsLanguage): List<NewsItem> =
    if (language.code == null) this else filter { it.lang == language.code }

/** The stories of one day, under a heading such as "Today". */
data class NewsDay(val label: String, val items: List<NewsItem>)

private val dayFormat = LocalDate.Format {
    day(Padding.NONE); chars(" "); monthName(MonthNames.ENGLISH_ABBREVIATED); chars(" "); year()
}

/** [this] (newest first) under day headings: "Today", "Yesterday", then "3 Nov 2026"; days in [zone]. */
fun List<NewsItem>.byDay(today: LocalDate, zone: TimeZone): List<NewsDay> =
    groupBy { Instant.fromEpochMilliseconds(it.published).toLocalDateTime(zone).date }
        .map { (date, items) ->
            val label = when (date) {
                today -> "Today"
                LocalDate.fromEpochDays(today.toEpochDays() - 1) -> "Yesterday"
                else -> date.format(dayFormat)
            }
            NewsDay(label, items)
        }

/** Events from [month] onward, wrapping round the year, so the next one comes first. */
fun List<BookEvent>.fromMonth(month: Int): List<BookEvent> =
    sortedBy { (it.month - month + 12) % 12 }

private val monthNames = listOf(
    "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December",
)

/** "Usually in October, Frankfurt". */
fun BookEvent.usually(): String =
    "Usually in ${monthNames.getOrElse(month - 1) { "?" }}" + if (place.isBlank()) "" else ", $place"
