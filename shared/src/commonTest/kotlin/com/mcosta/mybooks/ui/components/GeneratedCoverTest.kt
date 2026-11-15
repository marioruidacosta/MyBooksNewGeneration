package com.mcosta.mybooks.ui.components

import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals

class GeneratedCoverTest {
    /** The widget draws its own generated covers: they must be the app's colour for the title. */
    @Test
    fun coverColourMatchesTheGeneratedCover() {
        assertEquals(COVER_COLORS[coverColorIndex("Stoner")].toArgb(), coverColorArgb("Stoner"))
    }
}
