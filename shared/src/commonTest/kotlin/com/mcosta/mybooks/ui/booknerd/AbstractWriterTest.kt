package com.mcosta.mybooks.ui.booknerd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AbstractWriterTest {
    private fun answers(language: BotLanguage, genre: Genre = Genre.DRAMA, tone: Tone = Tone.MOVING) = when (language) {
        BotLanguage.PT -> IdeaAnswers(genre, "Teodoro, um farmacêutico tímido", "Lisboa, em 1910", "descobre uma carta que nunca devia ter lido", tone)
        BotLanguage.EN -> IdeaAnswers(genre, "Teodoro, a shy pharmacist", "Lisbon, in 1910", "he finds a letter he was never meant to read", tone)
    }

    private fun words(text: String) = text.split(Regex("\\s+")).count { it.isNotBlank() }

    @Test
    fun everyCombinationIsComplete() {
        for (language in BotLanguage.entries) for (genre in Genre.entries) for (tone in Tone.entries) for (variant in 0..2) {
            val a = answers(language, genre, tone)
            val text = writeAbstract(a, language, variant)
            val what = "$language $genre $tone $variant"
            assertFalse('{' in text || '}' in text, "$what has a gap: $text")
            assertTrue(a.character in text, "$what lacks the character")
            assertTrue(a.setting in text, "$what lacks the setting")
            assertTrue(words(text) in 120..180, "$what has ${words(text)} words: $text")
        }
    }

    @Test
    fun shortAnswersStillMakeAFullAbstract() {
        for (language in BotLanguage.entries) for (genre in Genre.entries) {
            val text = writeAbstract(IdeaAnswers(genre, "Ana", "Porto", "perde tudo", Tone.LIGHT), language, 0)
            assertTrue(words(text) in 120..180, "$language $genre: ${words(text)} words")
        }
    }

    @Test
    fun variantsDiffer() {
        val a = answers(BotLanguage.EN, Genre.MYSTERY, Tone.DARK)
        val texts = (0..2).map { writeAbstract(a, BotLanguage.EN, it) }
        assertEquals(3, texts.toSet().size)
    }

    @Test
    fun variantWraps() {
        val a = answers(BotLanguage.PT, Genre.FANTASY, Tone.FUNNY)
        assertEquals(writeAbstract(a, BotLanguage.PT, 0), writeAbstract(a, BotLanguage.PT, 3))
    }

    /** Review focus: a 400-character answer is cut to 120 characters at a word, and the abstract stays readable. */
    @Test
    fun longAnswersAreCut() {
        val long = "Teodoro " + "muito ".repeat(70) + "fim"
        val text = writeAbstract(IdeaAnswers(Genre.DRAMA, long, "Lisboa", "algo corre mal", Tone.MOVING), BotLanguage.PT, 0)
        val kept = cutAnswer(long)
        assertTrue(kept.length <= ANSWER_MAX)
        assertTrue(kept in text)
        assertFalse("fim" in text.split(' '))
        assertTrue(words(text) in 120..180, "${words(text)} words")
    }

    @Test
    fun settingPrepositionAndTrailingDotsAreTidied() {
        val text = writeAbstract(IdeaAnswers(Genre.DRAMA, "Ana.", "in Lisbon", "she leaves!", Tone.LIGHT), BotLanguage.EN, 0)
        assertFalse("in in " in text)
        assertFalse(".." in text)
        assertFalse("!." in text)
    }

    @Test
    fun genreAndToneFromChipsAndWords() {
        assertEquals(Genre.SCIENCE_FICTION, genreOf("Science fiction"))
        assertEquals(Genre.SCIENCE_FICTION, genreOf("ficção científica"))
        assertEquals(Genre.MYSTERY, genreOf("policial"))
        assertEquals(Genre.ROMANCE, genreOf("Romance"))
        assertEquals(Genre.HISTORICAL, genreOf("histórico"))
        assertEquals(Genre.COMEDY, genreOf("comédia"))
        assertEquals(Genre.DRAMA, genreOf("unknown"))
        assertEquals(Tone.DARK, toneOf("Escuro"))
        assertEquals(Tone.FUNNY, toneOf("Funny"))
        assertEquals(Tone.SUSPENSEFUL, toneOf("tenso"))
        assertEquals(Tone.MOVING, toneOf("whatever"))
    }
}
