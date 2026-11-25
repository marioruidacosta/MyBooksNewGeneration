package com.mcosta.mybooks.data.books

import com.mcosta.mybooks.data.lookup.FakeHttpServer
import com.mcosta.mybooks.data.lookup.FakeResponse
import com.mcosta.mybooks.ui.detail.factRows
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WikidataFactsTest {
    private val server = FakeHttpServer()
    private val facts = WikidataFacts(server.httpClient(), baseUrl = server.baseUrl)

    private fun json(body: String) = FakeResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun search(vararg ids: String) = json("""{"search":[${ids.joinToString(",") { """{"id":"$it"}""" }}]}""")
    private fun ref(id: String) = """{"mainsnak":{"datavalue":{"value":{"entity-type":"item","id":"$id"}}}}"""
    private fun labels(vararg pairs: Pair<String, String>) =
        pairs.joinToString(",", "{", "}") { (lang, value) -> """"$lang":{"language":"$lang","value":"$value"}""" }

    /** Blindness as Wikidata has it, next to the film of the same name. */
    private val candidates = json(
        """{"entities":{
          "Q838405":{"id":"Q838405","labels":${labels("pt" to "Ensaio sobre a Cegueira", "en" to "Blindness")},"claims":{"P57":[${ref("Q310343")}]}},
          "Q826428":{"id":"Q826428","labels":${labels("pt" to "Ensaio sobre a Cegueira", "en" to "Blindness")},"claims":{
            "P50":[${ref("Q37060")}],
            "P577":[{"mainsnak":{"datavalue":{"value":{"time":"+1995-00-00T00:00:00Z","precision":9}}}}],
            "P407":[${ref("Q5146")}],
            "P166":[${ref("Q130553019")},${ref("Q999")}],
            "P136":[${ref("Q944250")},${ref("Q24925")}],
            "P155":[${ref("Q1351463")}],
            "P156":[${ref("Q1216446")}]
          }}
        }}""",
    )
    private val writers = json("""{"entities":{"Q37060":{"id":"Q37060","labels":${labels("pt" to "José Saramago")}}}}""")
    private val names = json(
        """{"entities":{
          "Q130553019":{"id":"Q130553019","labels":{}},
          "Q999":{"id":"Q999","labels":${labels("en" to "Some Prize")}},
          "Q944250":{"id":"Q944250","labels":${labels("pt" to "ficção científica social")}},
          "Q24925":{"id":"Q24925","labels":${labels("pt" to "ficção científica")}},
          "Q1351463":{"id":"Q1351463","labels":${labels("pt" to "O Evangelho segundo Jesus Cristo")}},
          "Q1216446":{"id":"Q1216446","labels":${labels("pt" to "Ensaio sobre a Lucidez", "en" to "Seeing")}},
          "Q5146":{"id":"Q5146","labels":${labels("pt" to "português", "en" to "Portuguese")}}
        }}""",
    )

    @Test
    fun findsTheWorkByItsTitleAndAuthor() = runTest {
        server.enqueue(search("Q838405", "Q826428"))
        server.enqueue(candidates)
        server.enqueue(writers)
        server.enqueue(names)
        val found = facts.facts("Blindness", "José Saramago")
        assertEquals(
            BookFacts(
                firstPublished = "1995", originalTitle = "Ensaio sobre a Cegueira", originalLanguage = "português",
                genres = listOf("ficção científica social", "ficção científica"), awards = listOf("Some Prize"),
                follows = "O Evangelho segundo Jesus Cristo", followedBy = "Ensaio sobre a Lucidez",
                pageUrl = "https://www.wikidata.org/wiki/Q826428",
            ),
            found,
        )
        val first = server.takeRequest().path
        assertTrue(first.startsWith("/w/api.php?action=wbsearchentities"), first)
        assertTrue("search=Blindness" in first, first)
    }

    @Test
    fun aWorkBySomeoneElseIsNotThisBook() = runTest {
        repeat(2) {
            server.enqueue(search("Q838405", "Q826428"))
            server.enqueue(candidates)
            server.enqueue(writers)
        }
        assertNull(facts.facts("Blindness", "Somebody Else"))
    }

    @Test
    fun isAskedOnlyOncePerBook() = runTest {
        server.enqueue(search())
        server.enqueue(search())
        assertNull(facts.facts("Unknown Book", "An Author"))
        assertNull(facts.facts("unknown book", "An Author"))
        assertEquals(2, server.requestCount) // pt and en, the first time only
        assertNull(facts.facts("No Author Book", ""))
    }

    @Test
    fun noConnectionIsNoFacts() = runTest {
        server.enqueue(FakeResponse().setResponseCode(500).setBody("down"))
        assertNull(facts.facts("Offline Book", "An Author"))
    }

    @Test
    fun theRowsLeaveOutWhatTheBookAlreadySays() {
        val f = BookFacts("1995", "Ensaio sobre a Cegueira", "português", listOf("a", "b", "c", "d"), emptyList(), null, "Seeing", "u")
        assertEquals(
            listOf(
                "First published" to "1995",
                "Original title" to "Ensaio sobre a Cegueira (português)",
                "Genre" to "a, b, c",
                "Followed by" to "Seeing",
            ),
            factRows(f, "Blindness"),
        )
        assertFalse(factRows(f, "Ensaio Sobre a Cegueira").any { it.first == "Original title" })
    }
}
