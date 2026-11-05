package com.mcosta.mybooks.domain

import kotlinx.serialization.Serializable

/**
 * Stored by name, so declaration order is free (it's the order buttons show). WISHLIST is shown as
 * "To buy"; the name is kept so existing databases and backup files stay valid.
 */
@Serializable
enum class ReadingStatus { READING, TO_READ, READ, WISHLIST }

data class Book(
    val id: Long = 0,
    val isbn: String? = null,
    val title: String,
    val authors: String = "",
    val publisher: String? = null,
    val publishedYear: Int? = null,
    val pageCount: Int? = null,
    val coverUrl: String? = null,
    val description: String? = null,
    val status: ReadingStatus = ReadingStatus.TO_READ,
    val rating: Int? = null,
    val notes: String = "",
    val dateAdded: Long = 0,
    val dateRead: Long? = null,
    /** When reading started; set on first move to READING, kept when the book is finished. */
    val dateStarted: Long? = null,
    /** Reading progress as a page number, when entered by page. */
    val currentPage: Int? = null,
    /** Reading progress 0..100; computed from [currentPage] when the page count is known. */
    val progressPercent: Int? = null,
    /** Public review, shared with the community when sharing is on (Notes stay private). */
    val review: String = "",
    /** Your own labels ("classics", "gift from Ana"), in the order added; Goodreads shelves become tags. */
    val tags: List<String> = emptyList(),
    /** The series the book belongs to, and its place in it as written ("1", "2.5", "1-3"). */
    val series: String? = null,
    val seriesNumber: String? = null,
    /** Who has the book, and since when; null when it is at home. */
    val lentTo: String? = null,
    val lentOn: Long? = null,
)
