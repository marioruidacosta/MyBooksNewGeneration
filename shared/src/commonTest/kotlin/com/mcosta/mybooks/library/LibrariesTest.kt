package com.mcosta.mybooks.library

import com.mcosta.mybooks.data.Preferences
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LibrariesTest {
    private class MemoryPreferences : Preferences {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String) {
            values[key] = value
        }
    }

    private class FakeSpace(
        override val id: LibraryId,
        private val openFails: Boolean = false,
        private val onClose: (FakeSpace) -> Unit,
    ) : LibraryHandle {
        var opened = false
        override suspend fun open() {
            if (openFails) error("can't read it")
            opened = true
        }

        var closed = false
        override suspend fun close() {
            onClose(this)
            closed = true
        }
    }

    private val settings = MemoryPreferences()
    private val opened = mutableListOf<LibraryId>()
    private val log = mutableListOf<String>()
    private val deleted = mutableListOf<LibraryId>()
    private var failing: LibraryId? = null
    private var failingOpen: LibraryId? = null
    private lateinit var libraries: Libraries<FakeSpace>

    private fun libraries(scope: kotlinx.coroutines.CoroutineScope) = Libraries(
        factory = { id ->
            if (id == failing) error("can't open")
            opened += id
            FakeSpace(id, openFails = id == failingOpen) { log += "close ${it.id.key} while ${libraries.active.value.id.key} active" }
        },
        deviceSettings = settings,
        scope = scope,
        delete = { deleted += it },
    ).also { libraries = it }

    @Test
    fun activateSwapsAndClosesTheOld() = runTest {
        val libraries = libraries(backgroundScope)
        libraries.start()
        val local = libraries.active.value
        assertEquals(LibraryId.Local, local.id)

        libraries.activate(LibraryId.Account("a"))
        runCurrent()

        assertEquals(LibraryId.Account("a"), libraries.active.value.id)
        assertTrue(local.closed)
        assertEquals(listOf("close local while a active"), log)
        assertEquals("a", settings.values["library.active"])
    }

    @Test
    fun activatingTheActiveLibraryChangesNothing() = runTest {
        val libraries = libraries(backgroundScope)
        libraries.start()
        val local = libraries.active.value

        libraries.activate(LibraryId.Local)

        assertSame(local, libraries.active.value)
        assertFalse(local.closed)
        assertEquals(listOf<LibraryId>(LibraryId.Local), opened)
    }

    @Test
    fun aLibraryThatCannotOpenLeavesTheActiveOneOpen() = runTest {
        val libraries = libraries(backgroundScope)
        libraries.start()
        val local = libraries.active.value
        failing = LibraryId.Account("a")

        assertFailsWith<IllegalStateException> { libraries.activate(LibraryId.Account("a")) }

        assertSame(local, libraries.active.value)
        assertFalse(local.closed)
        assertEquals(null, settings.values["library.active"])
    }

    @Test
    fun startsOnLastActiveKey() = runTest {
        settings.values["library.active"] = "a"
        val libraries = libraries(backgroundScope)

        libraries.start()

        assertEquals(LibraryId.Account("a"), libraries.active.value.id)
        assertEquals(listOf<LibraryId>(LibraryId.Account("a")), opened)
    }

    @Test
    fun startsOnLocalWithoutALastActiveKey() = runTest {
        val libraries = libraries(backgroundScope)

        libraries.start()

        assertEquals(LibraryId.Local, libraries.active.value.id)
    }

    @Test
    fun startsOnLocalWhenTheLastLibraryCannotOpen() = runTest {
        settings.values["library.active"] = "a"
        failing = LibraryId.Account("a")
        val libraries = libraries(backgroundScope)

        libraries.start()

        assertEquals(LibraryId.Local, libraries.active.value.id)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun startsOnLocalWhenTheLastKeyIsInvalid() = runTest {
        settings.values["library.active"] = "../x"
        val libraries = libraries(backgroundScope)

        libraries.start()

        assertEquals(LibraryId.Local, libraries.active.value.id)
    }

    @Test
    fun removeRefusesActiveAndLocal() = runTest {
        val libraries = libraries(backgroundScope)
        libraries.start()
        libraries.activate(LibraryId.Account("a"))

        assertFailsWith<IllegalArgumentException> { libraries.remove(LibraryId.Local) }
        assertFailsWith<IllegalArgumentException> { libraries.remove(LibraryId.Account("a")) }
        assertTrue(deleted.isEmpty())

        libraries.activate(LibraryId.Local)
        libraries.remove(LibraryId.Account("a"))

        assertEquals(listOf<LibraryId>(LibraryId.Account("a")), deleted)
    }

    @Test
    fun aHeldLibraryClosesOnlyOnceReleased() = runTest {
        val libraries = libraries(backgroundScope)
        libraries.start()
        val local = libraries.active.value
        val screen = libraries.hold(local)!!

        libraries.activate(LibraryId.Account("a"))
        runCurrent()

        assertFalse(local.closed)
        // No longer active: a screen composing it now gets nothing, and shows the new one instead.
        assertEquals(null, libraries.hold(local))
        screen.release()
        screen.release()
        runCurrent()
        assertTrue(local.closed)
    }

    @Test
    fun activateOpensTheNewLibraryBeforeSwapping() = runTest {
        val libraries = libraries(backgroundScope)
        libraries.start()
        failingOpen = LibraryId.Account("a")

        assertFailsWith<IllegalStateException> { libraries.activate(LibraryId.Account("a")) }
        assertEquals(LibraryId.Local, libraries.active.value.id)

        libraries.activate(LibraryId.Account("b"))
        assertTrue(libraries.active.value.opened)
    }

    @Test
    fun startDoesNotOpenAndOpenActiveFallsBackToLocal() = runTest {
        settings.values["library.active"] = "a"
        failingOpen = LibraryId.Account("a")
        val libraries = libraries(backgroundScope)

        libraries.start()
        assertEquals(LibraryId.Account("a"), libraries.active.value.id)
        assertFalse(libraries.active.value.opened)

        libraries.openActive()

        assertEquals(LibraryId.Local, libraries.active.value.id)
        assertTrue(libraries.active.value.opened)
        assertTrue(deleted.isEmpty())
    }
}
