package com.mcosta.mybooks.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldTimesTest {
    private fun row(fields: Map<String, JsonElement>, times: Map<String, Long>) = SyncRow(SyncTable.BOOKS, "b", fields, times)

    @Test
    fun timesJsonRoundTrips() {
        val t = mapOf("title" to 1000L, "rating" to 9000L)
        assertEquals(t, parseTimes(timesJson(t)))
        assertEquals(emptyMap(), parseTimes("not json"))
    }

    @Test
    fun stampMarksOnlyChangedFields() {
        val old = mapOf<String, JsonElement>("title" to JsonPrimitive("T"), "rating" to JsonPrimitive(3))
        val new = mapOf<String, JsonElement>("title" to JsonPrimitive("T"), "rating" to JsonPrimitive(4))
        val times = stamp(old, new, mapOf("title" to 1000L, "rating" to 1000L), 9000)
        assertEquals(1000L, times["title"])
        assertEquals(9000L, times["rating"])
        assertEquals(mapOf("title" to 9000L, "rating" to 9000L), stamp(null, new, emptyMap(), 9000))
    }

    @Test
    fun remoteNewerFieldWins() {
        val local = row(mapOf("title" to JsonPrimitive("old")), mapOf("title" to 1000))
        val remote = row(mapOf("title" to JsonPrimitive("new")), mapOf("title" to 2000))
        val m = mergeFields(local, remote)
        assertEquals(JsonPrimitive("new"), m.fields["title"])
        assertEquals(2000L, m.times["title"])
    }

    @Test
    fun remoteOlderFieldDoesNotOverwriteLocal() {
        val local = row(mapOf("notes" to JsonPrimitive("mine"), "rating" to JsonPrimitive(1)), mapOf("notes" to 5000, "rating" to 100))
        val remote = row(mapOf("notes" to JsonPrimitive("theirs"), "rating" to JsonPrimitive(4)), mapOf("notes" to 4000, "rating" to 6000))
        val m = mergeFields(local, remote)
        assertEquals(JsonPrimitive("mine"), m.fields["notes"])
        assertEquals(JsonPrimitive(4), m.fields["rating"])
        // equal time keeps local
        val tie = mergeFields(local, row(mapOf("notes" to JsonPrimitive("x")), mapOf("notes" to 5000)))
        assertEquals(JsonPrimitive("mine"), tie.fields["notes"])
    }

    @Test
    fun remoteDeletedNewerHidesRow() {
        val local = row(mapOf("deleted" to JsonPrimitive(false), "title" to JsonPrimitive("a")), mapOf("deleted" to 1000, "title" to 6000))
        val remote = row(mapOf("deleted" to JsonPrimitive(true)), mapOf("deleted" to 7000))
        assertEquals(JsonPrimitive(true), mergeFields(local, remote).fields["deleted"])
    }

    @Test
    fun missingLocalTimeLosesToAnyRemoteTime() {
        val local = row(mapOf("title" to JsonPrimitive("old")), emptyMap())
        val remote = row(mapOf("title" to JsonPrimitive("new")), mapOf("title" to 0))
        assertEquals(JsonPrimitive("new"), mergeFields(local, remote).fields["title"])
    }

    @Test
    fun noLocalTakesRemote() {
        val remote = row(mapOf("title" to JsonPrimitive("new")), mapOf("title" to 5))
        assertEquals(remote, mergeFields(null, remote))
    }
}
