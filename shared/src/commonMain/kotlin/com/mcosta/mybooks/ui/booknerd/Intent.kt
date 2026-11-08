package com.mcosta.mybooks.ui.booknerd

/** What the user asked BookNerd for. */
enum class BotIntent { BOOKS, FRIENDS, ABSTRACT }

/** The request a typed message makes (null when none) and the language it was written in. */
data class Understood(val intent: BotIntent?, val language: BotLanguage)

private val BOOK_WORDS = listOf("livro", "livros", "ler", "leitura", "sugere", "sugestao", "recomenda", "book", "books", "read", "suggest", "recommend")
private val FRIEND_WORDS = listOf("amigo", "amigos", "amiga", "pessoas", "leitores", "gente", "friend", "friends", "people", "readers", "buddies")
private val ABSTRACT_WORDS = listOf("resumo", "sinopse", "ideia", "enredo", "historia", "abstract", "blurb", "synopsis", "idea", "plot", "story")

private val PORTUGUESE_WORDS = setOf(
    "livro", "livros", "ler", "leitura", "sugere", "sugestao", "recomenda",
    "amigo", "amigos", "amiga", "pessoas", "leitores", "gente",
    "resumo", "sinopse", "ideia", "enredo", "historia",
)
private const val PORTUGUESE_LETTERS = "ãõçéêáàóôíú"

private val PLAIN = mapOf(
    'á' to 'a', 'à' to 'a', 'â' to 'a', 'ã' to 'a', 'ä' to 'a', 'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e',
    'í' to 'i', 'ì' to 'i', 'î' to 'i', 'ï' to 'i', 'ó' to 'o', 'ò' to 'o', 'ô' to 'o', 'õ' to 'o', 'ö' to 'o',
    'ú' to 'u', 'ù' to 'u', 'û' to 'u', 'ü' to 'u', 'ç' to 'c', 'ñ' to 'n',
)

/** Lower case, accents removed: "SUGESTÃO" → "sugestao". */
internal fun fold(text: String): String = text.lowercase().map { PLAIN[it] ?: it }.joinToString("")

/**
 * What a typed message asks for, by keywords (whole words or word starts, ignoring case and accents). A friend word
 * wins, then an abstract word, then a book word: book words are the most general and also turn up in the others
 * ("find friends who read books", "an idea for a book"). Portuguese when a Portuguese keyword or letter appears.
 */
fun understand(text: String): Understood {
    val words = fold(text).split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
    fun hits(keywords: List<String>) = words.filter { w -> keywords.any { w.startsWith(it) } }
    val friends = hits(FRIEND_WORDS)
    val abstract = hits(ABSTRACT_WORDS)
    val books = hits(BOOK_WORDS)
    val intent = when {
        friends.isNotEmpty() -> BotIntent.FRIENDS
        abstract.isNotEmpty() -> BotIntent.ABSTRACT
        books.isNotEmpty() -> BotIntent.BOOKS
        else -> null
    }
    val portuguese = text.lowercase().any { it in PORTUGUESE_LETTERS } ||
        words.any { w -> PORTUGUESE_WORDS.any { w.startsWith(it) } }
    return Understood(intent, if (portuguese) BotLanguage.PT else BotLanguage.EN)
}
