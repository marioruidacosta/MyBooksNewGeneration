package com.mcosta.mybooks.domain

/** ISBN validation and normalization. Every valid ISBN is returned as 13 digits. */
object Isbn {

    /** Returns the ISBN-13 for [raw] (ISBN-10 or ISBN-13, hyphens/spaces allowed), or null if invalid. */
    fun normalize(raw: String): String? {
        val s = raw.filterNot { it == '-' || it.isWhitespace() }.uppercase()
        return when (s.length) {
            10 -> if (isValidIsbn10(s)) toIsbn13(s.substring(0, 9)) else null
            13 -> if (s.all { it.isDigit() } && (s.startsWith("978") || s.startsWith("979")) &&
                eanCheckDigit(s.substring(0, 12)) == s[12].digitToInt()
            ) s else null
            else -> null
        }
    }

    private fun isValidIsbn10(s: String): Boolean {
        if (!s.substring(0, 9).all { it.isDigit() }) return false
        val last = s[9]
        val lastValue = when {
            last == 'X' -> 10
            last.isDigit() -> last.digitToInt()
            else -> return false
        }
        val sum = (0 until 9).sumOf { (10 - it) * s[it].digitToInt() } + lastValue
        return sum % 11 == 0
    }

    private fun toIsbn13(isbn10Body: String): String {
        val body = "978$isbn10Body"
        return body + eanCheckDigit(body)
    }

    private fun eanCheckDigit(first12: String): Int {
        val sum = first12.withIndex().sumOf { (i, c) -> c.digitToInt() * if (i % 2 == 0) 1 else 3 }
        return (10 - sum % 10) % 10
    }
}

/** What a search query is, as an ISBN. */
sealed interface IsbnQuery {
    /** A valid ISBN-10 or ISBN-13, as [isbn13]. */
    data class Valid(val isbn13: String) : IsbnQuery

    /** Shaped like an ISBN (10 characters, the last may be X, or 13 digits) but its check digit is wrong. */
    data object Invalid : IsbnQuery

    data object NotIsbn : IsbnQuery
}

/** Reads [raw] (hyphens and spaces allowed) as an ISBN query. */
fun isbnQuery(raw: String): IsbnQuery {
    Isbn.normalize(raw)?.let { return IsbnQuery.Valid(it) }
    val s = raw.filterNot { it == '-' || it.isWhitespace() }.uppercase()
    val shaped = (s.length == 13 && s.all { it.isDigit() }) ||
        (s.length == 10 && s.take(9).all { it.isDigit() } && (s[9].isDigit() || s[9] == 'X'))
    return if (shaped) IsbnQuery.Invalid else IsbnQuery.NotIsbn
}
