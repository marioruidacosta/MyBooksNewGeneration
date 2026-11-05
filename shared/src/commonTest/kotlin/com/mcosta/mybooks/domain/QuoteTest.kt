package com.mcosta.mybooks.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class QuoteTest {
    @Test
    fun linesBrokenByThePageAreJoined() {
        assertEquals(
            "Era uma vez um rei que fez promessa de levantar um convento em Mafra.",
            tidyPageText("Era uma vez um rei que fez\npromessa de levantar um\n  convento em Mafra.\n"),
        )
    }

    @Test
    fun paragraphsAreKept() {
        assertEquals("First paragraph here.\n\nSecond one.", tidyPageText("First paragraph\nhere.\n\n\nSecond\none."))
    }

    @Test
    fun aWordHyphenatedAcrossLinesIsPutBackTogether() {
        assertEquals("the construction of the convent", tidyPageText("the construc-\ntion of the convent"))
        // A dash before a capital starts a new part, and a lone dash is kept.
        assertEquals("Lisboa- Mafra and so - on", tidyPageText("Lisboa-\nMafra and so -\non"))
    }

    @Test
    fun windowsLineEndsAndBlankTextAreHandled() {
        assertEquals("one two", tidyPageText("one\r\ntwo"))
        assertEquals("", tidyPageText(" \n \n"))
    }
}
