package com.mcosta.mybooks.reader

import com.mcosta.mybooks.domain.EbookFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    private val scope = TestScope(UnconfinedTestDispatcher())
    private var now = 0L
    private val saved = mutableListOf<String>()
    private var finishAt: String? = null
    private var markedRead = 0
    private val file = ReaderFile(EbookFormat.EPUB, "/files/ebooks/7.epub", "{\"p\":0}")

    private fun vm(load: ReaderFile? = file) = ReaderViewModel(
        load = { load },
        save = { position, _ -> saved += position; position == finishAt },
        markRead = { markedRead++ },
        scope = scope,
        clock = { now },
    )

    @Test
    fun opensTheFileAtItsSavedPlace() {
        assertEquals(ReaderLoad.Ready(file), vm().load.value)
    }

    @Test
    fun savesPositionAtMostEvery3sAndOnLeaving() {
        val vm = vm()
        for ((t, p) in listOf(0L to "p0", 1_000L to "p1", 2_000L to "p2", 4_000L to "p4")) {
            now = t
            vm.position(p, 0.1)
        }
        assertEquals(listOf("p0", "p4"), saved)
        now = 5_000
        vm.position("p5", 0.2)
        vm.leave()
        assertEquals(listOf("p0", "p4", "p5"), saved)
        vm.leave()
        assertEquals(3, saved.size)                  // nothing new to save
    }

    @Test
    fun finishPromptOnceThenMarkAsRead() {
        finishAt = "end"
        val vm = vm()
        assertFalse(vm.finishPrompt.value)
        vm.position("end", 1.0)
        assertTrue(vm.finishPrompt.value)
        vm.markFinished()
        assertFalse(vm.finishPrompt.value)
        assertEquals(1, markedRead)
    }

    @Test
    fun notNowJustClosesThePrompt() {
        finishAt = "end"
        val vm = vm()
        vm.position("end", 1.0)
        vm.dismissFinish()
        assertFalse(vm.finishPrompt.value)
        assertEquals(0, markedRead)
    }

    @Test
    fun missingFileClosesWithMessage() {
        assertEquals(ReaderLoad.Failed("Couldn't open this book"), vm(load = null).load.value)
    }
}
