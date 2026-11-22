package com.mcosta.mybooks.share

import com.mcosta.mybooks.data.Preferences

/** A synced preference per excluded book: `share_excluded.<book uuid>`. The server enforces it too. */
const val SHARE_EXCLUDED_PREFIX = "share_excluded."

private const val EXCLUDED = "1"
private const val NOT_EXCLUDED = "0"

/**
 * Set (and synced) once this device's older, phone-only exclusions are queued under their cloud ids, or when the
 * community was joined on the website: from then on the website can trust the synced exclusions, and post a book it hasn't seen shared.
 */
const val SHARE_EXCLUSIONS_SYNCED = "share_exclusions.synced"

/** Whether [key] is one of the synced preferences of "Don't share this book". */
fun isShareExclusionKey(key: String): Boolean = key.startsWith(SHARE_EXCLUDED_PREFIX) || key == SHARE_EXCLUSIONS_SYNCED

/** Set once this library's older, phone-only exclusions have been uploaded. Not synced (no [SHARE_EXCLUDED_PREFIX]). */
private const val CARRIED_OVER = "share_excluded_carried"

/**
 * "Don't share this book" as every device sees it. The value is kept in the library's preferences so that it syncs
 * like them, newest change winning, with one key per book so two devices excluding different books can't overwrite
 * each other.
 */
class ShareExclusions(
    private val prefs: Preferences,
    /** Every key in [prefs]. */
    private val storedKeys: () -> Collection<String>,
    /** Told (with the key) after a change made on this device; used by sync. */
    private val onChanged: suspend (String) -> Unit,
    /** Writes a value to disk now, before it is queued: a value still in memory at a crash would upload as "removed". */
    private val persist: suspend (key: String, value: String) -> Unit = { _, _ -> },
) {
    /** The keys that sync. A removed exclusion stays listed, so its removal reaches the other devices. */
    val keys: List<String> get() = storedKeys().filter(::isShareExclusionKey)

    /** The value as uploaded; null when the book isn't excluded (the sync row is then a deleted one). */
    fun value(key: String): String? = prefs.get(key)?.takeIf { it == EXCLUDED }

    fun isExcluded(uuid: String): Boolean = value(keyOf(uuid)) != null

    /** A change made here: kept and queued for the other devices. */
    suspend fun mark(uuid: String, excluded: Boolean) {
        if (isExcluded(uuid) == excluded) return
        record(keyOf(uuid), if (excluded) EXCLUDED else NOT_EXCLUDED)
    }

    /** As [mark] for an exclusion, even when it is already set here: an earlier upload may not have got through. */
    internal suspend fun upload(uuid: String) = record(keyOf(uuid), EXCLUDED)

    /** Tells the other devices that the exclusions they see are complete. */
    internal suspend fun markSynced() {
        if (value(SHARE_EXCLUSIONS_SYNCED) == null) record(SHARE_EXCLUSIONS_SYNCED, EXCLUDED)
    }

    private suspend fun record(key: String, value: String) {
        prefs.set(key, value)
        persist(key, value)
        onChanged(key)
    }

    /** The value from another device (null: removed there). Not reported as a change, or it would be pushed back. */
    fun pulled(key: String, value: String?) {
        prefs.set(key, if (value == EXCLUDED) EXCLUDED else NOT_EXCLUDED)
    }

    internal val carriedOver: Boolean get() = prefs.get(CARRIED_OVER) != null

    internal fun markCarriedOver() = prefs.set(CARRIED_OVER, EXCLUDED)

    private fun keyOf(uuid: String) = SHARE_EXCLUDED_PREFIX + uuid
}
