package com.mcosta.mybooks.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class IsbnQueryTest {
    @Test
    fun isbnQueries() {
        assertEquals(IsbnQuery.Valid("9789722036504"), isbnQuery("978-972-20-3650-4"))
        assertEquals(IsbnQuery.Valid("9789722036504"), isbnQuery(" 972-20-3650-5 "))   // ISBN-10
        assertEquals(IsbnQuery.Invalid, isbnQuery("9789722036505"))                      // wrong check digit
        assertEquals(IsbnQuery.Invalid, isbnQuery("972-20-3650-1"))
        assertEquals(IsbnQuery.NotIsbn, isbnQuery("Saramago"))
        assertEquals(IsbnQuery.NotIsbn, isbnQuery("1984"))
    }
}
