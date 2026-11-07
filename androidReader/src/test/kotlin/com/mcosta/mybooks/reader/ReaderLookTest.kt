package com.mcosta.mybooks.reader

import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.domain.EbookFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderLookTest {
    private val stored = mutableMapOf<String, String>()
    private val prefs = object : Preferences {
        override fun get(key: String) = stored[key]
        override fun set(key: String, value: String) { stored[key] = value }
    }

    @Test
    fun lookIsRememberedForAllBooks() {
        assertEquals(ReadingLook(), ReadingLooks(prefs).look.value)
        val look = ReadingLook(fontSize = 1.3, font = LookFont.SERIF, lineHeight = 1.5, theme = LookTheme.SEPIA)
        ReadingLooks(prefs).set(look)
        assertEquals(look, ReadingLooks(prefs).look.value)
    }

    @Test
    fun aDamagedSettingFallsBackToTheDefaults() {
        stored["reader.look"] = "size=huge;theme=PURPLE;font=serif"
        assertEquals(ReadingLook(font = LookFont.SERIF), ReadingLooks(prefs).look.value)
    }

    @Test
    fun sizeStaysInItsRange() {
        val looks = ReadingLooks(prefs)
        repeat(30) { looks.set(looks.look.value.bigger()) }
        assertEquals(MAX_FONT_SIZE, looks.look.value.fontSize, 0.001)
        repeat(30) { looks.set(looks.look.value.smaller()) }
        assertEquals(MIN_FONT_SIZE, looks.look.value.fontSize, 0.001)
    }

    @Test
    fun positionLabel() {
        assertEquals("Chapter 3 · 42%", positionLabel(EbookFormat.EPUB, "Chapter 3", null, 0.424, 0))
        assertEquals("42%", positionLabel(EbookFormat.EPUB, " ", null, 0.424, 0))
        assertEquals("Page 12 of 240", positionLabel(EbookFormat.PDF, null, 12, 0.05, 240))
        assertEquals("5%", positionLabel(EbookFormat.PDF, null, null, 0.05, 240))
    }

    @Test
    fun contentsAreFlattenedWithTheirDepth() {
        val toc = listOf(
            TocEntry("Part One", "p1", listOf(TocEntry("Chapter 1", "c1"), TocEntry("Chapter 2", "c2", listOf(TocEntry("Section", "s"))))),
            TocEntry("", "untitled"),
            TocEntry("Epilogue", "e"),
        )
        assertEquals(
            listOf("Part One" to 0, "Chapter 1" to 1, "Chapter 2" to 1, "Section" to 2, "Epilogue" to 0),
            flattenContents(toc).map { it.entry.title to it.depth },
        )
    }
}
