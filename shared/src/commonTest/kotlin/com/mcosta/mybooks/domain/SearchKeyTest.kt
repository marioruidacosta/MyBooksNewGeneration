package com.mcosta.mybooks.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Runs on Android here and on iOS in CI: both platforms must fold accents identically. */
class SearchKeyTest {
    @Test fun foldsPortugueseAccents() = assertEquals("Etica a Nicomaco", foldAccents("Ética a Nicómaco"))

    @Test fun foldsTildeAndCedilla() = assertEquals("Acao", foldAccents("Ação"))

    @Test fun keepsPlainText() = assertEquals("Dune 1965", foldAccents("Dune 1965"))

    @Test fun searchKeyIsFoldedAndLowercase() = assertEquals("jose saramago", searchKey("José Saramago"))

    /** The National Library's "Han, Kang" becomes "Kang Han": still the same author as the library's "Han Kang". */
    @Test fun authorKeyIgnoresNameOrder() {
        assertEquals(authorKey("Han Kang"), authorKey("Kang Han"))
        assertEquals(authorKey("José Saramago"), authorKey("saramago,  jose"))
    }

    @Test fun authorKeyKeepsDifferentPeopleApart() {
        assertNotEquals(authorKey("Han Kang"), authorKey("Han Yujoo"))
        assertNotEquals(authorKey("Ursula K. Le Guin"), authorKey("Ursula Le Guin Smith"))
    }
}
