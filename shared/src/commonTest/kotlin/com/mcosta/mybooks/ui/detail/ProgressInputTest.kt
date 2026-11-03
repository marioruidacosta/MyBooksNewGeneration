package com.mcosta.mybooks.ui.detail

import kotlin.test.Test
import kotlin.test.assertEquals

class ProgressInputTest {
    @Test
    fun pagesAndPercentages() {
        assertEquals(ProgressInput.Page(120), parseProgress(" 120 ", byPages = true))
        assertEquals(ProgressInput.Percent(100), parseProgress("100", byPages = false))
        assertEquals(ProgressInput.Invalid("Enter a page number"), parseProgress("", byPages = true))
        assertEquals(ProgressInput.Invalid("Enter a page number"), parseProgress("-3", byPages = true))
        assertEquals(ProgressInput.Invalid("Enter a percentage from 0 to 100"), parseProgress("101", byPages = false))
        assertEquals(ProgressInput.Invalid("Enter a percentage from 0 to 100"), parseProgress("half", byPages = false))
    }
}
