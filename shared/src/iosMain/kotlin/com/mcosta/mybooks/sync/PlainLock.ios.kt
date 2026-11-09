package com.mcosta.mybooks.sync

import platform.Foundation.NSRecursiveLock

internal actual fun plainLock(): PlainLock = object : PlainLock {
    private val lock = NSRecursiveLock()
    override fun lock() = lock.lock()
    override fun unlock() = lock.unlock()
}
