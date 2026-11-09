package com.mcosta.mybooks.sync

import kotlinx.coroutines.flow.Flow

/** A row the server set aside (e.g. a value over a length limit), with the server's [reason]. */
data class SetAside(val table: SyncTable, val id: String, val reason: String)

data class PushResult(val applied: List<Pair<SyncTable, String>>, val setAside: List<SetAside>)

/** [last] is the cursor to pass to the next pull; unchanged when [rows] is empty. */
data class PullPage(val rows: List<SyncRow>, val last: Long)

/** [ids] maps a device's local book id to the cloud id it was merged into. */
data class MergeResult(val ids: Map<String, String>, val setAside: List<SetAside>)

/**
 * A failed sync call. [rejected]: the server answered and refused the request itself (an HTTP 4xx other than an expired
 * session or a full store), so sending the same request again can't work; false when it was down or unreachable.
 */
class SyncException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
    val rejected: Boolean = false,
) : Exception(message, cause) {
    enum class Kind { OFFLINE, AUTH, STORAGE_FULL, OTHER }
}

interface SyncApi {
    /** Sends changes; callers batch: the server takes at most 200 per call. */
    suspend fun push(rows: List<SyncRow>): PushResult

    /** Rows with a seq after [after], oldest first. [max] is 1..1000 (server default 500). */
    suspend fun pull(after: Long, max: Int = 500): PullPage

    /**
     * Joins a device's books to the cloud library; callers batch: the server takes at most 100 books per call.
     * A pull row's `deleted` arrives as an ordinary entry in [SyncRow.fields] and [SyncRow.times].
     */
    suspend fun mergeLibrary(books: List<SyncRow>): MergeResult

    /** Emits whenever the owner's library changed on any device (this one included). */
    fun pings(owner: String): Flow<Unit>

    /** Stored at `<owner>/<bookUuid>.jpg`, replacing any earlier upload. */
    suspend fun uploadCover(owner: String, bookUuid: String, jpeg: ByteArray)

    /** The stored cover, or null when there is none. */
    suspend fun downloadCover(owner: String, bookUuid: String): ByteArray?
}
