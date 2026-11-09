package com.mcosta.mybooks.sync

import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.FakeBookmarkDao
import com.mcosta.mybooks.data.FakeChallengeDao
import com.mcosta.mybooks.data.FakeEbookDao
import com.mcosta.mybooks.data.FakeQuoteDao
import com.mcosta.mybooks.data.FakeSyncQueueDao
import com.mcosta.mybooks.data.FakeSyncStateDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** start() is called from the session observer and from the UI (foreground, pull-to-refresh) at the same time. */
class LibrarySyncConcurrencyTest {
    @Test
    fun concurrentStartsLeaveOneStoppableSession() {
        val engines = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Default + engines)
        val queue = FakeSyncQueueDao()
        val state = FakeSyncStateDao().apply { rows[mergedKey("owner")] = "1" }
        val db = SyncDatabase(FakeBookDao(), FakeQuoteDao(), FakeEbookDao(), FakeBookmarkDao(), FakeChallengeDao(), queue, state)
        val recorder = SyncRecorder(queue, { 0L }, { true }, { it() })
        val sync = LibrarySync(FakeSyncServer(), db, recorder, RemoteApplier(db, recorder), { 0L }, scope)
        val threads = Executors.newFixedThreadPool(8)
        try {
            repeat(300) { round ->
                sync.stop()
                val go = CountDownLatch(1)
                val calls = (1..8).map { threads.submit { go.await(); sync.start("owner") } }
                go.countDown()
                // A deadlock fails here, fast, instead of hanging the build.
                calls.forEach { it.get(10, TimeUnit.SECONDS) }
                // One session, the one stop() reaches; an orphan would keep running (and syncing) for good.
                assertEquals(1, engines.children.count { it.isActive }, "sessions running after round $round")
            }
            sync.stop()
            runBlocking { withTimeout(10_000) { engines.children.toList().forEach { it.join() } } }
            assertEquals(SyncStatus.Off, sync.status.value)
        } finally {
            threads.shutdownNow()
            scope.cancel()
        }
    }
}
