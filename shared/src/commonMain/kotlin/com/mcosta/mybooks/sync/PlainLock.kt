package com.mcosta.mybooks.sync

/** A lock that blocks (never spins) the threads waiting for it; reentrant. For short sections that never suspend. */
internal interface PlainLock {
    fun lock()
    fun unlock()
}

internal expect fun plainLock(): PlainLock

internal inline fun <T> PlainLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
