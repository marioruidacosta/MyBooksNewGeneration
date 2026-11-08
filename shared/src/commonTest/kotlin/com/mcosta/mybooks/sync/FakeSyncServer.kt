package com.mcosta.mybooks.sync

import com.mcosta.mybooks.data.authors.catalogTitleKey
import com.mcosta.mybooks.domain.searchKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * An in-memory sync server for one or more engines (two devices share one instance). It applies the real server's
 * per-field rule: an incoming time is capped at now + 60 000 ms, a field with no stored time counts as -1, an equal
 * time keeps the stored value. Everything is kept per [owner]; [seq] only ever grows.
 */
class FakeSyncServer(
    var owner: String = "owner",
    var clock: () -> Long = { 0L },
) : SyncApi {
    /** Thrown by the next call (of any kind), once. */
    var failNext: SyncException? = null

    private val rows = mutableMapOf<String, MutableMap<Pair<SyncTable, String>, SyncRow>>()
    private val covers = mutableMapOf<String, ByteArray>()
    private val pingFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private var seq = 0L

    /** The rows stored for [forOwner], by table and id. */
    fun stored(forOwner: String = owner): Map<Pair<SyncTable, String>, SyncRow> = rows[forOwner].orEmpty()

    private fun check() {
        failNext?.let { failNext = null; throw it }
    }

    private fun mine() = rows.getOrPut(owner) { mutableMapOf() }

    /** Merges [row] into the stored one; returns true when anything changed. */
    private fun mergeRow(row: SyncRow, id: String = row.id): Boolean {
        val cap = clock() + 60_000
        val key = row.table to id
        val old = mine()[key]
        val fields = old?.fields.orEmpty().toMutableMap()
        val times = old?.times.orEmpty().toMutableMap()
        var changed = old == null
        for ((f, v) in row.fields) {
            val t = minOf(row.times[f] ?: 0L, cap)
            if (t > (times[f] ?: -1L)) {
                fields[f] = v
                times[f] = t
                changed = true
            }
        }
        if (changed) mine()[key] = SyncRow(row.table, id, fields, times, ++seq)
        return changed
    }

    override suspend fun push(rows: List<SyncRow>): PushResult {
        check()
        require(rows.size <= 200) { "sync_push takes at most 200 changes" }
        rows.forEach { mergeRow(it) }
        if (rows.isNotEmpty()) pingFlow.tryEmit(owner)
        return PushResult(rows.map { it.table to it.id }, emptyList())
    }

    override suspend fun pull(after: Long, max: Int): PullPage {
        check()
        val page = mine().values.filter { it.seq > after }.sortedBy { it.seq }.take(max.coerceIn(1, 1000))
        return PullPage(page, page.lastOrNull()?.seq ?: after)
    }

    private fun text(row: SyncRow, field: String) = (row.fields[field] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun isDeleted(row: SyncRow) = (row.fields["deleted"] as? JsonPrimitive)?.booleanOrNull == true

    override suspend fun mergeLibrary(books: List<SyncRow>): MergeResult {
        check()
        require(books.size <= 100) { "merge_library takes at most 100 books" }
        val ids = mutableMapOf<String, String>()
        for (b in books) {
            val live = mine().values.filter { it.table == SyncTable.BOOKS && !isDeleted(it) }.sortedBy { it.seq }
            val isbn = text(b, "isbn").trim()
            val titleKey = catalogTitleKey(text(b, "title")).trim()
            val authorKey = searchKey(text(b, "authors").substringBefore(',')).trim()
            val match = (if (isbn.isNotEmpty()) live.firstOrNull { text(it, "isbn") == isbn } else null)
                ?: if (titleKey.isNotEmpty()) {
                    live.firstOrNull {
                        catalogTitleKey(text(it, "title")).trim() == titleKey &&
                            searchKey(text(it, "authors").substringBefore(',')).trim() == authorKey
                    }
                } else null
            val cloudId = match?.id ?: b.id
            mergeRow(b, cloudId)
            ids[b.id] = cloudId
        }
        if (ids.isNotEmpty()) pingFlow.tryEmit(owner)
        return MergeResult(ids, emptyList())
    }

    override fun pings(owner: String): Flow<Unit> = pingFlow.filter { it == owner }.map { }

    override suspend fun uploadCover(owner: String, bookUuid: String, jpeg: ByteArray) {
        check()
        covers["$owner/$bookUuid.jpg"] = jpeg
    }

    override suspend fun downloadCover(owner: String, bookUuid: String): ByteArray? {
        check()
        return covers["$owner/$bookUuid.jpg"]
    }
}
