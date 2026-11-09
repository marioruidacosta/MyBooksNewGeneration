package com.mcosta.mybooks.sync

import java.util.concurrent.locks.ReentrantLock

internal actual fun plainLock(): PlainLock = object : PlainLock {
    private val lock = ReentrantLock()
    override fun lock() = lock.lock()
    override fun unlock() = lock.unlock()
}
