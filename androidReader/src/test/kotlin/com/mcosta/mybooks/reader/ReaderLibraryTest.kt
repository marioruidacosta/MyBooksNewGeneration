package com.mcosta.mybooks.reader

import com.mcosta.mybooks.library.Libraries
import com.mcosta.mybooks.library.LibraryHandle
import com.mcosta.mybooks.library.LibraryId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLibraryTest {
    private class Handle(override val id: LibraryId) : LibraryHandle {
        @Volatile var closed = false
        override suspend fun close() {
            closed = true
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val libraries = Libraries<Handle>(
        factory = { Handle(it) },
        deviceSettings = null,
        scope = scope,
        delete = {},
    )
    private val ana = LibraryId.Account("11111111-1111-1111-1111-111111111111")

    @Test
    fun readerClosesWhenTheLibraryChanges() = runBlocking {
        withTimeout(10_000) {
            libraries.start()
            libraries.openActive()
            val reader = ReaderLibrary(libraries, scope)
            val shown = reader.space

            // Still the same library: the reader stays.
            assertNull(withTimeoutOrNull(200) { reader.untilLeft() })
            assertFalse(reader.gone)

            // Signing in switches libraries: the reader is told to close, and its library waits for it.
            libraries.activate(ana)
            assertNotNull(withTimeoutOrNull(5_000) { reader.untilLeft().let { true } })
            assertFalse(shown.closed)

            // Once the reader lets go, the library it showed is closed.
            reader.release()
            val deadline = System.currentTimeMillis() + 5_000
            while (!shown.closed && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(10)
            assertTrue(shown.closed)
        }
    }
}
