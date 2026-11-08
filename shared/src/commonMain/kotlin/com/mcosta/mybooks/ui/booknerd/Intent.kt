package com.mcosta.mybooks.ui.booknerd

/** What the user asked BookNerd for. */
enum class BotIntent { BOOKS, FRIENDS, ABSTRACT }

/**
 * The request a typed message makes (null when none or unclear), the language it was written in, and — when two
 * requests tie — the [options] to offer instead of guessing.
 */
data class Understood(val intent: BotIntent?, val language: BotLanguage, val options: List<BotIntent> = emptyList())

private val BOOK_WORDS = listOf("livro", "livros", "ler", "leitura", "sugere", "sugestao", "recomenda", "book", "books", "read", "suggest", "recommend")
private val FRIEND_WORDS = listOf("amigo", "amigos", "amiga", "pessoas", "leitores", "gente", "friend", "friends", "people", "readers", "buddies")
/** Abstract words that only mean "write me an abstract" (the others, like "story", also describe books). */
private val ABSTRACT_STRONG = listOf("resumo", "sinopse", "abstract", "blurb", "synopsis", "ideia", "ideias", "idea", "ideas")
private val ABSTRACT_WORDS = ABSTRACT_STRONG + listOf("enredo", "historia", "plot", "story")
/** Matched as whole words only ("idea" is not "ideal"). */
private val WHOLE_WORDS = setOf("ler", "read", "idea", "ideas", "ideia", "ideias", "gente")
/** Words that, just before a friend word, make it a request for people ("find friends", "quero novos amigos"). */
private val SEEK_WORDS = listOf(
    "find", "meet", "new", "more", "suggest", "recommend",
    "encontr", "conhec", "novo", "nova", "mais", "sugere", "recomenda", "procur",
)
private const val SEEK_WINDOW = 3

private val PORTUGUESE_WORDS = setOf(
    "livro", "livros", "ler", "leitura", "sugere", "sugestao", "recomenda",
    "amigo", "amigos", "amiga", "pessoas", "leitores", "gente",
    "resumo", "sinopse", "ideia", "enredo", "historia",
)
/** Common Portuguese words that are not English words ("no", "as", "do" and "e" are left out on purpose). */
private val PORTUGUESE_COMMON = setOf(
    "um", "uma", "uns", "umas", "em", "na", "nas", "nos", "num", "numa", "que", "com", "para", "da", "das", "dos",
    "os", "ele", "ela", "nao", "mas", "seu", "sua", "muito", "quer", "quero", "tenho", "descobre", "sobre", "porque",
)
private const val PORTUGUESE_LETTERS = "ãõçéêáàóôíú"

private val PLAIN = mapOf(
    'á' to 'a', 'à' to 'a', 'â' to 'a', 'ã' to 'a', 'ä' to 'a', 'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e',
    'í' to 'i', 'ì' to 'i', 'î' to 'i', 'ï' to 'i', 'ó' to 'o', 'ò' to 'o', 'ô' to 'o', 'õ' to 'o', 'ö' to 'o',
    'ú' to 'u', 'ù' to 'u', 'û' to 'u', 'ü' to 'u', 'ç' to 'c', 'ñ' to 'n',
)

/** Lower case, accents removed: "SUGESTÃO" → "sugestao". */
internal fun fold(text: String): String = text.lowercase().map { PLAIN[it] ?: it }.joinToString("")

private fun matches(word: String, keyword: String) = if (keyword in WHOLE_WORDS) word == keyword else word.startsWith(keyword)

/** Whether [text] reads as Portuguese: a Portuguese letter, keyword or common word. */
fun soundsPortuguese(text: String): Boolean {
    val words = fold(text).split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
    return text.lowercase().any { it in PORTUGUESE_LETTERS } ||
        words.any { w -> w in PORTUGUESE_COMMON || PORTUGUESE_WORDS.any { matches(w, it) } }
}

/**
 * What a typed message asks for, by keywords (word starts, ignoring case and accents):
 * - a friend word right after a seeking word ("find friends", "novos amigos") → FRIENDS;
 * - an abstract-only word ("blurb", "sinopse") → ABSTRACT;
 * - otherwise the request with most keywords; a tie offers the tied requests as [Understood.options].
 */
fun understand(text: String): Understood {
    val words = fold(text).split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
    fun isFriendWord(i: Int) = FRIEND_WORDS.any { matches(words[i], it) } && !(words[i] == "gente" && i > 0 && words[i - 1] == "a")
    fun count(keywords: List<String>) = words.count { w -> keywords.any { matches(w, it) } }
    val friendAt = words.indices.filter { isFriendWord(it) }
    val sought = friendAt.any { i -> (maxOf(0, i - SEEK_WINDOW) until i).any { j -> SEEK_WORDS.any { words[j].startsWith(it) } } }
    val language = if (soundsPortuguese(text)) BotLanguage.PT else BotLanguage.EN
    val intent = when {
        sought -> BotIntent.FRIENDS
        words.any { w -> ABSTRACT_STRONG.any { matches(w, it) } } -> BotIntent.ABSTRACT
        else -> null
    }
    if (intent != null) return Understood(intent, language)
    val scores = mapOf(BotIntent.BOOKS to count(BOOK_WORDS), BotIntent.FRIENDS to friendAt.size, BotIntent.ABSTRACT to count(ABSTRACT_WORDS))
    val best = scores.values.max()
    if (best == 0) return Understood(null, language)
    val top = BotIntent.entries.filter { scores[it] == best }
    return if (top.size == 1) Understood(top.single(), language) else Understood(null, language, top)
}
