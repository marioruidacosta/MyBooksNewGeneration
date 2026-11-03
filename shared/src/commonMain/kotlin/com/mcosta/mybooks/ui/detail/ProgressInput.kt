package com.mcosta.mybooks.ui.detail

/** Typed reading progress: a page, a percentage, or what's wrong with it. */
sealed interface ProgressInput {
    data class Page(val page: Int) : ProgressInput
    data class Percent(val percent: Int) : ProgressInput
    data class Invalid(val message: String) : ProgressInput
}

/** A page number, or a percentage when [byPages] is false. */
fun parseProgress(input: String, byPages: Boolean): ProgressInput {
    val value = input.trim().toIntOrNull()
    return when {
        byPages -> if (value == null || value < 0) ProgressInput.Invalid("Enter a page number") else ProgressInput.Page(value)
        value == null || value !in 0..100 -> ProgressInput.Invalid("Enter a percentage from 0 to 100")
        else -> ProgressInput.Percent(value)
    }
}
