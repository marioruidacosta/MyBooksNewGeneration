package com.mcosta.mybooks.ui.booknerd

import kotlin.test.Test
import kotlin.test.assertEquals

class IntentTest {
    @Test
    fun eachIntentInBothLanguages() {
        assertEquals(Understood(BotIntent.BOOKS, BotLanguage.PT), understand("sugere-me um livro"))
        assertEquals(Understood(BotIntent.BOOKS, BotLanguage.EN), understand("suggest a book"))
        assertEquals(Understood(BotIntent.FRIENDS, BotLanguage.PT), understand("quero novos amigos"))
        assertEquals(Understood(BotIntent.FRIENDS, BotLanguage.EN), understand("find me readers"))
        assertEquals(Understood(BotIntent.ABSTRACT, BotLanguage.PT), understand("tenho uma ideia para um livro"))
        assertEquals(Understood(BotIntent.ABSTRACT, BotLanguage.EN), understand("write a blurb"))
    }

    @Test
    fun accentsAndCapitalsIgnored() {
        assertEquals(Understood(BotIntent.BOOKS, BotLanguage.PT), understand("SUGESTÃO"))
        assertEquals(BotIntent.ABSTRACT, understand("Sinopse").intent)
        assertEquals(BotIntent.ABSTRACT, understand("uma história").intent)
    }

    @Test
    fun tieBreaks() {
        assertEquals(BotIntent.FRIENDS, understand("find friends who read books").intent)
        assertEquals(BotIntent.ABSTRACT, understand("a book idea").intent)
    }

    /** Final review, Important 4: everyday book requests that mention people or stories stay book requests. */
    @Test
    fun bookRequestsMentioningPeopleOrStories() {
        assertEquals(BotIntent.BOOKS, understand("sugere-me um livro para oferecer a uma amiga").intent)
        assertEquals(BotIntent.BOOKS, understand("sugere-me um livro de história").intent)
        assertEquals(BotIntent.BOOKS, understand("a gente quer ler").intent)
        assertEquals(BotIntent.BOOKS, understand("an ideal book for the beach").intent)
        assertEquals(BotIntent.FRIENDS, understand("suggest some friends").intent)
        assertEquals(BotIntent.FRIENDS, understand("quero conhecer leitores").intent)
    }

    /** Final review, Important 4: when two requests tie, nothing is guessed; the options are offered. */
    @Test
    fun tiesOfferTheOptions() {
        val u = understand("a book for my friend's birthday")
        assertEquals(null, u.intent)
        assertEquals(listOf(BotIntent.BOOKS, BotIntent.FRIENDS), u.options)
        assertEquals(listOf(BotIntent.BOOKS, BotIntent.ABSTRACT), understand("a book with a great plot").options)
        assertEquals(emptyList(), understand("hello").options)
    }

    /** Final review, Important 3: Portuguese is recognised from common words, not only keywords and accents. */
    @Test
    fun portugueseFromCommonWords() {
        assertEquals(BotLanguage.PT, understand("descobre uma carta").language)
        assertEquals(BotLanguage.PT, understand("Lisboa, em 1910").language)
        assertEquals(BotLanguage.EN, understand("no idea, as usual").language)
        assertEquals(BotLanguage.EN, understand("an e-book").language)
    }

    @Test
    fun wordStartsOnly() {
        assertEquals(BotIntent.BOOKS, understand("bookshop").intent)
        assertEquals(null, understand("already").intent)
        assertEquals(null, understand("mybooks").intent)
    }

    @Test
    fun noMatch() {
        assertEquals(Understood(null, BotLanguage.PT), understand("olá"))
        assertEquals(Understood(null, BotLanguage.EN), understand("hello"))
        assertEquals(Understood(null, BotLanguage.EN), understand(""))
    }
}
