package com.mcosta.mybooks.sync

import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.db.BookEntity
import com.mcosta.mybooks.data.db.SyncStateDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Photos fetched at once. */
internal const val DOWNLOADS_AT_ONCE = 3

/** A cover the cloud didn't have is asked for again after this long (its upload may simply not have happened yet). */
internal const val MISSING_RETRY_MS = 10 * 60 * 1000L

/** `sync_state` key holding the file name of the photo last uploaded for a book. */
internal fun coverKey(uuid: String) = "cover:$uuid"

/** `sync_state` key noting that the cloud had no photo for a book (value: the cover's time, then when it was asked). */
internal fun coverMissingKey(uuid: String) = "cover-missing:$uuid"

/** `sync_state` key holding the cover_url time of the photo last downloaded for a book. */
internal fun coverGotKey(uuid: String) = "cover-got:$uuid"

/**
 * Cover photos travel apart from the rows (which only say "photo"): uploaded after a push, downloaded after a pull.
 * The network calls run outside any lock; what they leave behind (a file, a marker) is written through [commit], which
 * the engine makes wait for a pass in progress and refuse once the session has stopped.
 */
class CoverSync(
    private val api: SyncApi,
    private val covers: CoverStore,
    private val state: SyncStateDao,
    private val clock: () -> Long = { 0L },
    private val commit: suspend (suspend () -> Unit) -> Unit = { it() },
) {
    private val _full = MutableStateFlow(false)

    /** The cloud refused an upload for lack of room: uploads stop until [retryUploads] (the next start). */
    val full: StateFlow<Boolean> = _full.asStateFlow()

    fun retryUploads() {
        _full.value = false
    }

    /** Uploads each book's photo that isn't uploaded yet (a different file name from the marker counts as new). */
    suspend fun uploadPending(owner: String, books: List<BookEntity>) {
        for (book in books) {
            if (_full.value) return
            val ref = book.coverUrl
            val name = covers.nameOf(ref) ?: continue
            if (book.deleted || state.get(coverKey(book.uuid)) == name) continue
            val jpeg = covers.read(ref) ?: continue
            try {
                api.uploadCover(owner, book.uuid, jpeg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SyncException) {
                if (e.kind == SyncException.Kind.STORAGE_FULL) _full.value = true
                // Anything else (offline, signed out): the next pass tries again.
                return
            } catch (_: Exception) {
                return
            }
            commit { state.set(coverKey(book.uuid), name) }
        }
    }

    /** Fetches the photos of pulled books (`cover:<uuid>.jpg`) not on this device, [DOWNLOADS_AT_ONCE] at a time. */
    suspend fun downloadMissing(owner: String, books: List<BookEntity>) {
        val wanted = books.filter { book ->
            val ref = book.coverUrl
            !book.deleted && covers.nameOf(ref) == "${book.uuid}.jpg" && needsDownload(book) && !recentlyMissing(book)
        }
        if (wanted.isEmpty()) return
        val gate = Semaphore(DOWNLOADS_AT_ONCE)
        coroutineScope {
            wanted.map { book -> async { gate.withPermit { download(owner, book) } } }.awaitAll()
        }
    }

    /** The file is missing, or the cover was replaced (a newer cover_url time) since the one downloaded. */
    private suspend fun needsDownload(book: BookEntity): Boolean {
        if (!covers.exists(book.coverUrl)) return true
        // No record (restored backup, lost sync state): counts as time 0, so the file is refreshed once.
        val got = state.get(coverGotKey(book.uuid))?.toLongOrNull() ?: 0L
        return coverTime(book) > got
    }

    private suspend fun recentlyMissing(book: BookEntity): Boolean {
        val (coverTime, asked) = state.get(coverMissingKey(book.uuid))?.split('@')?.takeIf { it.size == 2 } ?: return false
        return coverTime == coverTime(book).toString() && (asked.toLongOrNull() ?: return false) + MISSING_RETRY_MS > clock()
    }

    private fun coverTime(book: BookEntity) = parseTimes(book.fieldTimes)["cover_url"] ?: 0L

    private suspend fun download(owner: String, book: BookEntity) {
        val jpeg = try {
            api.downloadCover(owner, book.uuid)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Tried again on the next pull.
            return
        }
        val time = coverTime(book)
        val name = "${book.uuid}.jpg"
        if (jpeg == null) {
            commit { state.set(coverMissingKey(book.uuid), "${coverTime(book)}@${clock()}") }
            return
        }
        commit {
            covers.put(name, jpeg)
            // Fetched, so not sent back.
            state.set(coverKey(book.uuid), name)
            state.set(coverGotKey(book.uuid), time.toString())
            // No longer missing (an empty value reads as no record).
            if (state.get(coverMissingKey(book.uuid)) != null) state.set(coverMissingKey(book.uuid), "")
        }
    }
}
