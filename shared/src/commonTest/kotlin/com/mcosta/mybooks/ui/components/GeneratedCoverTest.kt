package com.mcosta.mybooks.ui.components

import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneratedCoverTest {
    @Test
    fun aTitleAlwaysGetsTheSameColour() {
        assertEquals(coverColorIndex("O Castelo"), coverColorIndex("O Castelo"))
        assertEquals(coverColorIndex("Ósmio"), coverColorIndex("osmio"))           // ignores case and accents
        // Pinned: the colour must not change between versions or devices.
        assertEquals(8, coverColorIndex("Dune"))
    }

    @Test
    fun coloursAreInRangeAndVaried() {
        val titles = listOf("Dune", "Emma", "O Castelo", "Bichos", "Solaris", "Stoner", "Orbital", "Fumo", "James", "Maus", "", "2666")
        val indexes = titles.map(::coverColorIndex)
        assertTrue(indexes.all { it in COVER_COLORS.indices })
        assertTrue(indexes.toSet().size >= 6)
    }

    /** The widget draws its own generated covers: they must be the app's colour for the title. */
    @Test
    fun coverColourMatchesTheGeneratedCover() {
        assertEquals(COVER_COLORS[coverColorIndex("Stoner")].toArgb(), coverColorArgb("Stoner"))
    }
}
