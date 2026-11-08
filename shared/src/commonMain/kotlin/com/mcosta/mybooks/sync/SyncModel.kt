package com.mcosta.mybooks.sync

import kotlinx.serialization.json.JsonElement

/** The synced tables; [wire] is the name used in sync requests (`library_<wire>` on the server). */
enum class SyncTable(val wire: String) {
    BOOKS("books"), QUOTES("quotes"), CHALLENGES("challenges"), EBOOKS("ebooks"), BOOKMARKS("bookmarks"), PREFS("prefs")
}

/**
 * One row as it travels: [fields] by the server's column name, [times] the last-change time (ms) of each field.
 * `deleted` is an ordinary field (a JSON boolean) with its own time.
 */
data class SyncRow(
    val table: SyncTable,
    val id: String,
    val fields: Map<String, JsonElement>,
    val times: Map<String, Long>,
    val seq: Long = 0,
)
