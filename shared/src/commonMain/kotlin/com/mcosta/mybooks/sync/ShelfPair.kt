package com.mcosta.mybooks.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A book's place on a physical shelf: the two fields change together and are never mixed from two copies. */
val SHELF_FIELDS: Set<String> = setOf("shelf_id", "shelf_position")

private fun SyncRow.shelf(): String? = (fields["shelf_id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun SyncRow.shelfTime(): Long = SHELF_FIELDS.maxOf { times[it] ?: 0L }

/**
 * [this], the merge of [kept] and [other], with the place of one of them whole: the copy on a real shelf, or [kept]
 * when both are. The time makes that choice win wherever the other copy's place is stored.
 */
fun SyncRow.withShelfPair(kept: SyncRow, other: SyncRow, isValid: (String) -> Boolean = { true }): SyncRow {
    if (SHELF_FIELDS.all { kept.fields[it] == other.fields[it] }) return this
    val chosen = if (kept.shelf()?.let(isValid) == true || other.shelf()?.let(isValid) != true) kept else other
    val rejected = if (chosen === kept) other else kept
    val chosenTime = chosen.shelfTime()
    val rejectedTime = rejected.shelfTime()
    val wins = chosenTime > rejectedTime || (chosen === kept && chosenTime == rejectedTime)
    val time = if (wins) chosenTime else rejectedTime + 1
    val pair: Map<String, JsonElement> = SHELF_FIELDS.associateWith { chosen.fields[it] ?: JsonNull }
    return copy(fields = fields + pair, times = times + SHELF_FIELDS.associateWith { time })
}
