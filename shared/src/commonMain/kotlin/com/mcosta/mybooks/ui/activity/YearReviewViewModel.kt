package com.mcosta.mybooks.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.ChallengeRepository
import com.mcosta.mybooks.domain.YearReview
import com.mcosta.mybooks.domain.yearReview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.TimeZone

/** A year's reading, told as a review; null until loaded. */
class YearReviewViewModel(
    repo: BookRepository,
    challenges: ChallengeRepository,
    val year: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {
    val review: StateFlow<YearReview?> = combine(repo.observeBooks(null, ""), challenges.observe()) { books, goals ->
        yearReview(books, year, timeZone, goals.firstOrNull { it.year == year }?.goal)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
