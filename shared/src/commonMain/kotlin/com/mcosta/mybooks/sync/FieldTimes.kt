package com.mcosta.mybooks.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

fun parseTimes(json: String): Map<String, Long> = try {
    (Json.parseToJsonElement(json) as? JsonObject)?.mapNotNull { (k, v) ->
        (v as? JsonPrimitive)?.longOrNull?.let { k to it }
    }?.toMap() ?: emptyMap()
} catch (_: Exception) {
    emptyMap()
}

fun timesJson(times: Map<String, Long>): String =
    JsonObject(times.entries.sortedBy { it.key }.associate { it.key to JsonPrimitive(it.value) }).toString()

/** Times after a local edit: every field whose value changed (all of them when [old] is null) gets [now]; an unchanged field keeps its time, or stays untimed. */
fun stamp(old: Map<String, JsonElement>?, new: Map<String, JsonElement>, oldTimes: Map<String, Long>, now: Long): Map<String, Long> =
    new.keys.mapNotNull { f ->
        val t = if (old == null || old[f] != new[f]) now else oldTimes[f]
        t?.let { f to it }
    }.toMap()

/**
 * Per field, the remote value wins only when its time is newer than the local one (a missing local time counts
 * as -1; equal keeps local). The server's rule, minus its time cap.
 */
fun mergeFields(local: SyncRow?, remote: SyncRow): SyncRow {
    if (local == null) return remote
    val fields = local.fields.toMutableMap()
    val times = local.times.toMutableMap()
    for ((f, v) in remote.fields) {
        val rt = remote.times[f] ?: continue
        if (rt > (local.times[f] ?: -1L)) {
            fields[f] = v
            times[f] = rt
        }
    }
    return SyncRow(remote.table, remote.id, fields, times, remote.seq)
}
