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

    /** Minor 6: a cut answer ends with "…", and the sentence after it doesn't add a stop to it. */
    @Test
    fun cutAnswersEndWithAnEllipsis() {
        val long = "Teodoro " + "muito ".repeat(70) + "fim"
        assertTrue(cutAnswer(long).endsWith("…"))
        assertFalse(cutAnswer("Teodoro, um farmacêutico").endsWith("…"))
        for (genre in Genre.entries) for (v in 0..2) {
            val text = writeAbstract(IdeaAnswers(genre, long, "Lisboa", long, Tone.MOVING), BotLanguage.PT, v)
            assertFalse("…." in text, "$genre $v: $text")
        }
    }

    /** Minor 5: an answer that starts with a capitalised article or pronoun reads right mid-sentence. */
    @Test
    fun leadingArticleIsLoweredMidSentence() {
        val en = writeAbstract(IdeaAnswers(Genre.DRAMA, "A shy pharmacist", "Lisbon", "She loses her job", Tone.LIGHT), BotLanguage.EN, 0)
        assertTrue("Meet a shy pharmacist." in en, en)
        assertTrue(": she loses her job." in en, en)
        val pt = writeAbstract(IdeaAnswers(Genre.DRAMA, "Um farmacêutico tímido", "Lisboa", "Ela perde o emprego", Tone.LIGHT), BotLanguage.PT, 0)
        assertTrue("Conheça um farmacêutico tímido." in pt, pt)
        assertTrue(": ela perde o emprego." in pt, pt)
        val names = writeAbstract(IdeaAnswers(Genre.DRAMA, "Teodoro", "Lisboa", "Ana parte", Tone.LIGHT), BotLanguage.PT, 0)
        assertTrue("Conheça Teodoro." in names && ": Ana parte." in names, names)
        val start = writeAbstract(IdeaAnswers(Genre.DRAMA, "A shy pharmacist", "Lisbon", "x", Tone.LIGHT), BotLanguage.EN, 1)
        assertTrue(start.startsWith("A shy pharmacist has"), start)
    }

    /** Minor 4: the Portuguese text doesn't assume the main character's gender. */
    @Test
    fun portugueseIsGenderNeutral() {
        for (genre in Genre.entries) for (tone in Tone.entries) for (v in 0..2) {
            val text = writeAbstract(IdeaAnswers(genre, "Ana", "Lisboa", "perde tudo", tone), BotLanguage.PT, v).lowercase()
            listOf("realmente disposta", "realmente disposto", "herói", "heroína").forEach { assertFalse(it in text, "$genre $tone $v has '$it': $text") }
        }
    }

    @Test
    fun settingPrepositionAndTrailingDotsAreTidied() {
        val text = writeAbstract(IdeaAnswers(Genre.DRAMA, "Ana.", "in Lisbon", "she leaves!", Tone.LIGHT), BotLanguage.EN, 0)
        assertFalse("in in " in text)
        assertFalse(".." in text)
        assertFalse("!." in text)
    }

    /** Final review, Important 2: a place typed with its own preposition or article keeps it, in every template. */
    @Test
    fun placeKeepsItsPreposition() {
        val cases = listOf(
            BotLanguage.PT to listOf(
                "no Porto, nos anos 60" to "no Porto, nos anos 60",
                "na aldeia da avó" to "na aldeia da avó",
                "numa aldeia do Alentejo" to "numa aldeia do Alentejo",
                "durante a guerra" to "durante a guerra",
                "o Porto" to "no Porto",
                "Lisboa" to "em Lisboa",
            ),
            BotLanguage.EN to listOf(
                "on Mars" to "on Mars",
                "at sea" to "at sea",
                "during the war" to "during the war",
                "Lisbon" to "in Lisbon",
            ),
        )
        val wrong = listOf("em no ", "em na ", "em numa ", "em durante", "em o ", "em porto", "a porto", "in on ", "in at ", "in during", "in mars", "in sea")
        for ((language, settings) in cases) for ((typed, phrase) in settings) for (genre in Genre.entries) for (v in 0..2) {
            val text = writeAbstract(IdeaAnswers(genre, "Ana", typed, "perde tudo", Tone.LIGHT), language, v)
            val lower = text.lowercase()
            assertTrue(phrase.lowercase() in lower, "$language $genre $v '$typed': $text")
            wrong.forEach { assertFalse(" $it" in " $lower", "$language $genre $v '$typed' has '$it': $text") }
        }
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
