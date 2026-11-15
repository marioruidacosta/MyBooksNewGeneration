package com.mcosta.mybooks.domain

/** Removes accents: decomposes characters (NFD) and drops the combining marks ("Ética" → "Etica"). */
expect fun foldAccents(text: String): String

/** Folds text for searching: lowercase and without accents ("Ética" → "etica", "José" → "jose"). */
fun searchKey(text: String): String = foldAccents(text).lowercase()

/**
 * Who an author is, whatever the name order: the folded words of [name], sorted ("Han Kang" and "Kang Han" → "han kang").
 * Catalogues file names as "Surname, Forename" and the app turns them round, which puts names already written surname
 * first (Han Kang, Mo Yan) the wrong way; comparing words, not order, still finds the same person.
 */
fun authorKey(name: String): String =
    searchKey(name).split(nonNameChars).filter(String::isNotEmpty).sorted().joinToString(" ")

private val nonNameChars = Regex("[^\\p{L}\\p{N}]+")

/** Unicode combining marks (category Mn), removed after decomposition. */
internal val combiningMarks = Regex("\\p{Mn}+")
