package com.mcosta.mybooks.social.supabase

import com.mcosta.mybooks.sync.MergeResult
import com.mcosta.mybooks.sync.PullPage
import com.mcosta.mybooks.sync.PushResult
import com.mcosta.mybooks.sync.SetAside
import com.mcosta.mybooks.sync.SyncApi
import com.mcosta.mybooks.sync.SyncException
import com.mcosta.mybooks.sync.SyncRow
import com.mcosta.mybooks.sync.SyncTable
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.storage
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Maps any failure to the [SyncException] kinds the sync status explains. */
internal fun mapSyncError(e: Throwable): SyncException {
    if (e is SyncException) return e
    val chain = generateSequence(e) { it.cause }.take(10).toList()
    val message = chain.joinToString(" ") { it.message.orEmpty() }
    val status = chain.filterIsInstance<RestException>().firstOrNull()?.statusCode
    return when {
        status == 401 || (e is PostgrestRestException && e.code == "PGRST301") ||
            message.contains("JWT expired", ignoreCase = true) ->
            SyncException(SyncException.Kind.AUTH, "session expired", e)
        status == 413 || message.contains("exceeded the maximum allowed size", ignoreCase = true) ||
            message.contains("quota", ignoreCase = true) ->
            SyncException(SyncException.Kind.STORAGE_FULL, "storage full", e)
        isTlsFailure(e) || chain.any { it is HttpRequestTimeoutException } || e is HttpRequestException || e is IOException ||
            message.contains("timeout has expired", ignoreCase = true) ->
            SyncException(SyncException.Kind.OFFLINE, "offline", e)
        else -> SyncException(SyncException.Kind.OTHER, e.message ?: "error", e)
    }
}

internal class SupabaseSync(
    private val client: SupabaseClient,
    private val pingChannel: (topic: String) -> PingChannel = { topic -> RealtimePingChannel(client, topic) },
) : SyncApi {
    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw mapSyncError(e)
    }

    private fun table(wire: String): SyncTable =
        SyncTable.entries.firstOrNull { it.wire == wire } ?: throw SyncException(SyncException.Kind.OTHER, "unknown table $wire")

    private fun change(row: SyncRow) = buildJsonObject {
        put("table", row.table.wire)
        put("id", row.id)
        put("fields", JsonObject(row.fields))
        put("times", JsonObject(row.times.mapValues { JsonPrimitive(it.value) }))
    }

    private fun setAside(e: JsonElement): SetAside {
        val o = e.jsonObject
        return SetAside(table(o.getValue("table").jsonPrimitive.content), o.getValue("id").jsonPrimitive.content,
            o["reason"]?.jsonPrimitive?.content.orEmpty())
    }

    override suspend fun push(rows: List<SyncRow>): PushResult = call {
        val result = client.postgrest.rpc("sync_push", buildJsonObject {
            put("changes", JsonArray(rows.map(::change)))
        }).decodeAs<JsonObject>()
        PushResult(
            applied = result["applied"]?.jsonArray.orEmpty().map {
                val o = it.jsonObject
                table(o.getValue("table").jsonPrimitive.content) to o.getValue("id").jsonPrimitive.content
            },
            setAside = result["set_aside"]?.jsonArray.orEmpty().map(::setAside),
        )
    }

    override suspend fun pull(after: Long, max: Int): PullPage = call {
        val result = client.postgrest.rpc("sync_pull", buildJsonObject {
            put("after", after)
            put("max", max)
        }).decodeAs<JsonObject>()
        val rows = result["rows"]?.jsonArray.orEmpty().map { e ->
            val o = e.jsonObject
            val times = o["times"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.long }
            val fields = o["fields"]?.jsonObject.orEmpty().toMutableMap()
            // The server returns the deleted marker beside the fields; callers see it as an ordinary field.
            o["deleted"]?.takeIf { it !is JsonNull }?.let { fields["deleted"] = it }
            SyncRow(table(o.getValue("table").jsonPrimitive.content), o.getValue("id").jsonPrimitive.content,
                fields, times, o["seq"]?.jsonPrimitive?.long ?: 0L)
        }
        PullPage(rows, result["last"]?.jsonPrimitive?.long ?: after)
    }

    override suspend fun mergeLibrary(books: List<SyncRow>): MergeResult = call {
        val result = client.postgrest.rpc("merge_library", buildJsonObject {
            put("books", JsonArray(books.map(::change)))
        }).decodeAs<JsonObject>()
        MergeResult(
            ids = result["ids"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content },
            // merge_library only merges books and names a rejected one by id and reason.
            setAside = result["set_aside"]?.jsonArray.orEmpty().map {
                val o = it.jsonObject
                SetAside(SyncTable.BOOKS, o.getValue("id").jsonPrimitive.content, o["reason"]?.jsonPrimitive?.content.orEmpty())
            },
        )
    }

    override fun pings(owner: String): Flow<Unit> = channelFlow {
        val channel = pingChannel("library:$owner")
        // Collect before subscribing so no early event is missed.
        val collector = launch { channel.events.collect { send(Unit) } }
        try {
            call { channel.subscribe() }
            awaitClose()
        } finally {
            collector.cancel()
            // Removes the channel from the client too, so repeated collection doesn't leave channels behind.
            withContext(NonCancellable) { runCatching { channel.remove() } }
        }
    }

    override suspend fun uploadCover(owner: String, bookUuid: String, jpeg: ByteArray) = call {
        client.storage.from("covers").upload("$owner/$bookUuid.jpg", jpeg) { upsert = true }
        Unit
    }

    override suspend fun downloadCover(owner: String, bookUuid: String): ByteArray? = try {
        call { client.storage.from("covers").downloadAuthenticated("$owner/$bookUuid.jpg") }
    } catch (e: SyncException) {
        if (isNotFound(e)) null else throw e
    }
}

/** The realtime channel behind [SyncApi.pings], so its lifecycle can be tested without a websocket. */
internal interface PingChannel {
    val events: Flow<Unit>
    suspend fun subscribe()
    suspend fun remove()
}

private class RealtimePingChannel(private val client: SupabaseClient, topic: String) : PingChannel {
    private val channel: RealtimeChannel = client.channel(topic) { isPrivate = true }
    override val events: Flow<Unit> = channel.broadcastFlow<JsonObject>("changed").map { }
    override suspend fun subscribe() = channel.subscribe(blockUntilSubscribed = true)
    override suspend fun remove() = client.realtime.removeChannel(channel)
}

/** A missing object: a real 404, or (older storage-api) a 400 whose body says statusCode 404. */
private fun isNotFound(e: SyncException): Boolean {
    val rest = generateSequence(e as Throwable) { it.cause }.filterIsInstance<RestException>().firstOrNull() ?: return false
    return rest.statusCode == 404 ||
        (rest.statusCode == 400 && "${rest.message} ${rest.description}".let { "404" in it || "not found" in it.lowercase() })
}
