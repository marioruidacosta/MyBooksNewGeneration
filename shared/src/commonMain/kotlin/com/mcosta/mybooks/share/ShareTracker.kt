package com.mcosta.mybooks.share

import com.mcosta.mybooks.data.backup.BackupShare
import com.mcosta.mybooks.data.db.ShareStateDao
import com.mcosta.mybooks.data.db.ShareStateEntity
import com.mcosta.mybooks.domain.Book
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
private fun randomShareId() = Uuid.random().toString()

/**
 * Told about every library change; marks the books whose public state changed as waiting to upload.
 * The uploading itself is the Publisher's job ([onPending] asks it to run).
 */
class ShareTracker(
    private val dao: ShareStateDao,
    /** The signed-in user while sharing is on (has a profile and "Share my reading activity"); else null. */
    private val activeOwner: () -> String?,
    private val onPending: () -> Unit,
    private val newShareId: () -> String = ::randomShareId,
    private val lock: ShareLock = ShareLock(),
    /** A book's sync uuid, which is also its public id; null when unknown (a random id is used then). */
    private val uuidOf: suspend (Long) -> String? = { null },
) {
    suspend fun changed(old: Book?, new: Book) {
        val owner = activeOwner() ?: return
        if (old != null && !publicFieldsDiffer(old, new)) return
        val marked = lock.mutex.withLock {
            val existing = dao.get(new.id)
            // "Don't share this book" is the user's choice for the book, whichever account made it.
            if (existing?.excluded == true) return@withLock false
            // Rows restored from a backup while signed out (owner "") are adopted; another account's are replaced.
            val row = existing?.takeIf { it.owner == owner || it.owner == "" }?.copy(owner = owner)
            val base = row ?: newRow(new.id, owner)
            // "Before" is the state before this batch of changes; the server uses it only if it has no row yet.
            dao.upsert((if (base.pending) base else base.withBefore(old)).copy(pending = true))
            true
        }
        if (marked) onPending()
    }

    suspend fun deleted(book: Book) {
        val marked = lock.mutex.withLock {
            val row = dao.get(book.id) ?: return@withLock false
            dao.upsert(row.copy(deleted = true, pending = true))
            true
        }
        if (marked) onPending()
    }

    suspend fun setExcluded(book: Book, excluded: Boolean) {
        val owner = activeOwner()
        val marked = lock.mutex.withLock {
            val row = dao.get(book.id)
            if (excluded) {
                val base = row ?: newRow(book.id, owner ?: "").withBefore(book)
                dao.upsert(base.copy(owner = owner ?: base.owner, excluded = true, pending = true))
                true
            } else {
                // Still waiting to be removed from the server: it never was, so it simply stays shared.
                if (row != null) {
                    if (row.pending) dao.upsert(row.copy(excluded = false, pending = false)) else dao.delete(book.id)
                }
                false
            }
        }
        if (marked) onPending()
    }

    /**
     * [book] arrived from another device, which publishes its own changes: nothing is marked waiting here. Its pulled
     * values become this book's "before" values, so the next change made here is published against them. A row still
     * waiting keeps its "before": its own change hasn't been published yet, and the pulled book already includes it.
     */
    suspend fun syncedFromElsewhere(book: Book) = lock.mutex.withLock {
        val row = dao.get(book.id) ?: return@withLock
        if (row.pending || row.deleted) return@withLock
        dao.upsert(row.withBefore(book))
    }

    /**
     * The first sign-in merge joined book [fromBookId] to book [toBookId] (the same book when only its uuid changed to
     * the cloud's). The share state moves to [toBookId], under that book's uuid as its public id:
     * - A public copy under another id is removed through the usual unshare path: a deleted row, waiting, parked under
     *   an id no book has, which the Publisher unpublishes and then drops.
     * - When both books had share state, [toBookId] keeps its own; "Don't share this book" from either side wins.
     * - A shared book that changed id is published again under the new one, against [book] (its state now), so the
     *   move itself adds nothing to the feed.
     */
    suspend fun moveShare(fromBookId: Long, toBookId: Long, book: Book? = null) {
        val newId = uuidOf(toBookId) ?: return
        val marked = lock.mutex.withLock {
            val from = dao.get(fromBookId) ?: return@withLock false
            var parkAt = minOf(0L, dao.all().minOfOrNull { it.bookId } ?: 0L)
            var retired = false

            /** Sends [row]'s public copy to the unshare path, unless it can't be on the server. */
            suspend fun retire(row: ShareStateEntity) {
                if (row.excluded && !row.pending && !row.deleted) return
                dao.upsert(row.copy(bookId = --parkAt, deleted = true, pending = true))
                retired = true
            }

            if (fromBookId != toBookId) dao.delete(fromBookId)
            if (from.deleted) {
                retire(from)
                return@withLock retired
            }
            val existing = if (fromBookId == toBookId) null else dao.get(toBookId)
            existing?.takeIf { it.deleted }?.let { retire(it) }
            val target = existing?.takeIf { !it.deleted }
            var kept = if (target == null) {
                from.copy(bookId = toBookId)
            } else {
                if (from.shareId != target.shareId) retire(from)
                val excluded = target.excluded || from.excluded
                target.copy(excluded = excluded, pending = target.pending || excluded != target.excluded)
            }
            if (kept.shareId != newId) {
                retire(kept)
                kept = kept.copy(shareId = newId)
                // Not waiting yet: publish (or, for "Don't share", unpublish) under the new id.
                if (!kept.pending && (kept.excluded || book != null)) {
                    kept = (if (kept.excluded) kept else kept.withBefore(book)).copy(pending = true)
                }
            }
            dao.upsert(kept)
            retired || kept.pending
        }
        if (marked) onPending()
    }

    fun observeExcluded(bookId: Long): Flow<Boolean> = dao.observe(bookId).map { it?.excluded == true }

    suspend fun exportShares(): Map<Long, BackupShare> =
        dao.all().filter { !it.deleted }.associate { it.bookId to BackupShare(it.shareId, it.excluded) }

    /**
     * A book restored from a backup: nothing to upload. A book that already has a row keeps it (its shared id
     * and anything still waiting); a "Don't share" from either side wins.
     */
    suspend fun restored(bookId: Long, share: BackupShare) = lock.mutex.withLock {
        val existing = dao.get(bookId)
        if (existing != null) {
            dao.upsert(existing.copy(excluded = existing.excluded || share.excluded))
        } else {
            dao.upsert(newRow(bookId, activeOwner() ?: "").copy(shareId = share.shareId, excluded = share.excluded))
        }
    }

    /**
     * Sharing is off for [owner] (switched off, or found off): the server holds nothing of theirs, so their
     * rows go. Rows marked "Don't share this book" stay, so the choice survives turning sharing back on.
     */
    suspend fun forgetOwner(owner: String) = lock.mutex.withLock {
        for (row in dao.all().filter { it.owner == owner }) {
            if (row.excluded) dao.upsert(row.withBefore(null).copy(pending = false)) else dao.delete(row.bookId)
        }
    }

    private suspend fun newRow(bookId: Long, owner: String) = ShareStateEntity(
        bookId = bookId, shareId = uuidOf(bookId) ?: newShareId(), owner = owner, hasBefore = false,
        beforeStatus = null, beforeRating = null, beforeProgress = null, beforeReview = null,
        pending = false, excluded = false, deleted = false,
    )

    private fun ShareStateEntity.withBefore(book: Book?) = copy(
        hasBefore = book != null,
        beforeStatus = book?.status,
        beforeRating = book?.rating,
        beforeProgress = book?.progressPercent,
        beforeReview = book?.review,
    )
}
