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
