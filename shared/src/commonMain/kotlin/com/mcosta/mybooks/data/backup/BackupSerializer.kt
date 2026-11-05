package com.mcosta.mybooks.data.backup

import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.Challenge
import com.mcosta.mybooks.domain.ReadingStatus
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A book's community sharing id and "Don't share this book" setting, kept in backups. */
data class BackupShare(val shareId: String, val excluded: Boolean)

/**
 * Library backup file format: `{ "version": 1, "exportedAt": ..., "books": [...], "challenges": [...] }`. The review,
 * sharing, challenge, tag, series and loan fields are optional, so older backups stay readable and the version stays 1.
 */
object BackupSerializer {
    private const val VERSION = 1
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** [shares] is keyed by [Book.id]. */
    fun encode(
        books: List<Book>,
        exportedAt: Long,
        shares: Map<Long, BackupShare> = emptyMap(),
        challenges: List<Challenge> = emptyList(),
        hiddenSuggestions: Collection<String> = emptyList(),
    ): String = json.encodeToString(
        BackupFile(
            VERSION, exportedAt, books.map { it.toBackup(shares[it.id]) }, challenges.map { BackupChallenge(it.year, it.goal) },
            hiddenSuggestions.sorted(),
        ),
    )

    /** Throws [BackupFormatException] if [text] is not a valid version-1 backup. */
    fun decode(text: String): List<Book> = decodeWithShares(text).map { it.first }

    /** Each book with its sharing info, if the backup has it. Throws like [decode]. */
    fun decodeWithShares(text: String): List<Pair<Book, BackupShare?>> =
        file(text).books.map { it.toBook() to it.shareId?.let { id -> BackupShare(id, it.shareExcluded) } }

    /** The reading challenges (none in backups made before them). Throws like [decode]. */
    fun decodeChallenges(text: String): List<Challenge> = file(text).challenges.map { Challenge(it.year, it.goal) }

    /** Books marked "Not interested" in suggestions (none in older backups). Throws like [decode]. */
    fun decodeHiddenSuggestions(text: String): List<String> = file(text).hiddenSuggestions

    private fun file(text: String): BackupFile {
        val file = try {
            json.decodeFromString<BackupFile>(text)
        } catch (e: SerializationException) {
            throw BackupFormatException("Not a MyBooks backup file", e)
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException("Not a MyBooks backup file", e)
        }
        if (file.version != VERSION) throw BackupFormatException("Unsupported backup version ${file.version}")
        return file
    }

    @Serializable
    private data class BackupFile(
        val version: Int,
        val exportedAt: Long = 0,
        val books: List<BackupBook> = emptyList(),
        val challenges: List<BackupChallenge> = emptyList(),
        val hiddenSuggestions: List<String> = emptyList(),
    )

    @Serializable
    private data class BackupChallenge(val year: Int, val goal: Int)

    @Serializable
    private data class BackupBook(
        val isbn: String? = null,
        val title: String = "",
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
        val dateStarted: Long? = null,
        val currentPage: Int? = null,
        val progressPercent: Int? = null,
        val review: String = "",
        val shareId: String? = null,
        val shareExcluded: Boolean = false,
        val tags: List<String> = emptyList(),
        val series: String? = null,
        val seriesNumber: String? = null,
        val lentTo: String? = null,
        val lentOn: Long? = null,
    ) {
        fun toBook() = Book(
            isbn = isbn, title = title, authors = authors, publisher = publisher, publishedYear = publishedYear,
            pageCount = pageCount, coverUrl = coverUrl, description = description, status = status,
            rating = rating, notes = notes, dateAdded = dateAdded, dateRead = dateRead,
            dateStarted = dateStarted, currentPage = currentPage, progressPercent = progressPercent, review = review,
            tags = tags, series = series, seriesNumber = seriesNumber, lentTo = lentTo, lentOn = lentOn,
        )
    }

    private fun Book.toBackup(share: BackupShare?) = BackupBook(
        isbn = isbn, title = title, authors = authors, publisher = publisher, publishedYear = publishedYear,
        pageCount = pageCount, coverUrl = coverUrl, description = description, status = status,
        rating = rating, notes = notes, dateAdded = dateAdded, dateRead = dateRead,
        dateStarted = dateStarted, currentPage = currentPage, progressPercent = progressPercent, review = review,
        shareId = share?.shareId, shareExcluded = share?.excluded ?: false,
        tags = tags, series = series, seriesNumber = seriesNumber, lentTo = lentTo, lentOn = lentOn,
    )
}
