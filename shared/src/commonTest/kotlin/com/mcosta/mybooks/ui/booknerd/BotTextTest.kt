package com.mcosta.mybooks.ui.booknerd

import com.mcosta.mybooks.social.model.Person
import com.mcosta.mybooks.social.model.SuggestedReader
import kotlin.test.Test
import kotlin.test.assertEquals

class BotTextTest {
    private val carla = Person("c", "carla", "Carla", null)
    private fun reader(shared: Int = 0, mutual: Int = 0, names: List<String> = emptyList(), title: String? = null) =
        SuggestedReader(carla, shared, mutual, names, title)

    @Test
    fun readerReasons() {
        assertEquals("Also loved Os Maias and 2 more", readerReason(reader(shared = 3, title = "Os Maias"), BotLanguage.EN))
        assertEquals("Também adorou Os Maias e mais 2", readerReason(reader(shared = 3, title = "Os Maias"), BotLanguage.PT))
        assertEquals("Also loved Os Maias", readerReason(reader(shared = 1, title = "Os Maias"), BotLanguage.EN))
        assertEquals("Friend of Ana and Rui", readerReason(reader(mutual = 2, names = listOf("Ana", "Rui")), BotLanguage.EN))
        assertEquals("Amigo de Ana e Rui", readerReason(reader(mutual = 2, names = listOf("Ana", "Rui")), BotLanguage.PT))
        assertEquals("Friend of Ana", readerReason(reader(mutual = 1, names = listOf("Ana")), BotLanguage.EN))
        assertEquals("Friend of Ana and Rui, and 2 more", readerReason(reader(mutual = 4, names = listOf("Ana", "Rui")), BotLanguage.EN))
        assertEquals("Amigo de Ana e Rui e mais 2", readerReason(reader(mutual = 4, names = listOf("Ana", "Rui")), BotLanguage.PT))
        assertEquals(
            "Also loved Os Maias · Friend of Ana",
            readerReason(reader(shared = 1, title = "Os Maias", mutual = 1, names = listOf("Ana")), BotLanguage.EN),
        )
    }
}
