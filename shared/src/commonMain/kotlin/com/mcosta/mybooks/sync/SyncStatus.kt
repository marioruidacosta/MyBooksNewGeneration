package com.mcosta.mybooks.sync

/** Where cloud sync stands, for Settings → Account. */
sealed interface SyncStatus {
    /** Signed out, or not started. */
    data object Off : SyncStatus
    data object Syncing : SyncStatus
    /** Everything sent and received at [at] (epoch ms). */
    data class Synced(val at: Long) : SyncStatus
    /** No network: [waiting] changes are kept and retried. */
    data class Offline(val waiting: Int) : SyncStatus
    /** Any other failure: [waiting] changes are kept and retried. */
    data class Failed(val waiting: Int) : SyncStatus
    /** The session expired: nothing is retried until the user signs in again. The queue is kept. */
    data object SignInAgain : SyncStatus
}

/** The status line's copy; [now] is the current time in ms. Empty while sync is off. */
fun SyncStatus.label(now: Long): String = when (this) {
    SyncStatus.Off -> ""
    SyncStatus.Syncing -> "Syncing…"
    is SyncStatus.Synced -> when (val minutes = ((now - at) / 60_000).coerceAtLeast(0)) {
        0L -> "Synced just now"
        1L -> "Synced 1 minute ago"
        else -> "Synced $minutes minutes ago"
    }
    is SyncStatus.Offline -> "Offline · $waiting ${if (waiting == 1) "change" else "changes"} waiting"
    is SyncStatus.Failed -> "Couldn't sync · Retry"
    SyncStatus.SignInAgain -> "Sign in again to sync"
}
