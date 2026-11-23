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

/**
 * Times after a local edit: every field whose value changed (all of them when [old] is null) gets [now], or one more than
 * its old time when the clock is not past that (a corrected clock, or another device stamped it later), so an edit is
 * never older than the value it replaces; an unchanged field keeps its time, or stays untimed. The fields in
 * [together] count as one: when any of them changed, all are stamped with the same time.
 */
fun stamp(
    old: Map<String, JsonElement>?,
    new: Map<String, JsonElement>,
    oldTimes: Map<String, Long>,
    now: Long,
    together: Set<String> = emptySet(),
): Map<String, Long> {
    fun changed(f: String) = old == null || old[f] != new[f]
    val pairTime = if (together.any(::changed)) together.maxOf { f -> oldTimes[f]?.let { maxOf(now, it + 1) } ?: now } else null
    return new.keys.mapNotNull { f ->
        val t = when {
            f in together && pairTime != null -> pairTime
            changed(f) -> oldTimes[f]?.let { maxOf(now, it + 1) } ?: now
            else -> oldTimes[f]
        }
        t?.let { f to it }
    }.toMap()
}

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
