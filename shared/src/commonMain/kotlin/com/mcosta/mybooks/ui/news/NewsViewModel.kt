package com.mcosta.mybooks.ui.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.data.news.BookEvent
import com.mcosta.mybooks.data.news.NewsDay
import com.mcosta.mybooks.data.news.NewsFeed
import com.mcosta.mybooks.data.news.NewsItem
import com.mcosta.mybooks.data.news.NewsLanguage
import com.mcosta.mybooks.data.news.NewsRepository
import com.mcosta.mybooks.data.news.NewsSource
import com.mcosta.mybooks.data.news.byDay
import com.mcosta.mybooks.data.news.fromMonth
import com.mcosta.mybooks.data.news.inLanguage
import com.mcosta.mybooks.ui.today as currentDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlin.time.Instant

internal const val NEWS_ERROR = "Couldn't load the news. Try again later."

internal const val NO_NEWS = "No news yet. Check back later."

/** How many headlines the Home card shows. */
const val HOME_NEWS_COUNT = 3

data class NewsState(
    /** Every story, newest first. */
    val items: List<NewsItem> = emptyList(),
    val sources: List<NewsSource> = emptyList(),
    val language: NewsLanguage = NewsLanguage.ALL,
    val loading: Boolean = true,
    /** The feed was downloaded (or read from the saved copy), even if it has no stories. */
    val loaded: Boolean = false,
    val error: String? = null,
    /** Set when the saved copy is shown because the download failed. */
    val savedAt: Instant? = null,
    /** The calendar, starting at this month. */
    val events: List<BookEvent> = emptyList(),
    val eventsLoading: Boolean = true,
    /** The stories under the day headings, for the chosen [language]. */
    val days: List<NewsDay> = emptyList(),
) {
    /** The stories for the chosen [language]. */
    val shown: List<NewsItem> get() = items.inLanguage(language)

    /** The newest stories whatever the language, for the Home card. */
    val latest: List<NewsItem> get() = items.take(HOME_NEWS_COUNT)

    fun sourceName(id: String): String = sources.firstOrNull { it.id == id }?.name ?: id
}

/** Book news: the stories (also the Home card's) and the book calendar. */
class NewsViewModel(
    private val repo: NewsRepository,
    /** Where the language chip is remembered between launches; null forgets it. */
    private val prefs: Preferences? = null,
    private val today: () -> LocalDate = { currentDate() },
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {
    private val _state = MutableStateFlow(NewsState(language = savedLanguage()))
    val state: StateFlow<NewsState> = _state.asStateFlow()

    init {
        load(force = false)
    }

    fun setLanguage(language: NewsLanguage) {
        prefs?.set(LANGUAGE_KEY, language.name)
        _state.update { regroup(it.copy(language = language)) }
    }

    private fun savedLanguage(): NewsLanguage = NewsLanguage.entries.firstOrNull { it.name == prefs?.get(LANGUAGE_KEY) } ?: NewsLanguage.ALL

    /** The "Retry" button. */
    fun retry() {
        _state.update { it.copy(loading = true, error = null, eventsLoading = true) }
        load(force = true)
    }

    /** Pull to refresh: downloads now, whatever the age of the saved copy. */
    suspend fun refresh() {
        fetch(force = true)
    }

    private fun load(force: Boolean) {
        viewModelScope.launch { fetch(force) }
    }

    /** The stories are shown as soon as they are in; the calendar follows, and loads even when the news does not. */
    private suspend fun fetch(force: Boolean) {
        try {
            val cached = repo.news(force)
            val feed: NewsFeed = cached.value
            _state.update {
                regroup(it.copy(items = feed.items, sources = feed.sources, savedAt = cached.savedAt.takeIf { cached.offline }, error = null, loaded = true))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed refresh never clears what is already shown.
            _state.update { if (it.items.isEmpty()) it.copy(error = NEWS_ERROR) else it }
        } finally {
            _state.update { it.copy(loading = false) }
        }
        try {
            val events = repo.events().fromMonth(today().month.number)
            _state.update { it.copy(events = events) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Unit
        } finally {
            _state.update { it.copy(eventsLoading = false) }
        }
    }

    private fun regroup(s: NewsState) = s.copy(days = s.shown.byDay(today(), timeZone))

    private companion object {
        const val LANGUAGE_KEY = "news.language"
    }
}
