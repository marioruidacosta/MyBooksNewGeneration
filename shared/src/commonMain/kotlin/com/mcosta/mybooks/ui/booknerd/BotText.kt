package com.mcosta.mybooks.ui.booknerd

import com.mcosta.mybooks.social.model.SuggestedReader

/** The language BookNerd answers in: the one the user wrote in. */
enum class BotLanguage { PT, EN }

/**
 * Why BookNerd suggests [r]: "Also loved Os Maias and 2 more", "Friend of Ana and Rui, and 2 more" ("Amigos em comum:
 * Ana e Rui", which doesn't assume a gender), or both joined
 * with " · " (taste first).
 */
fun readerReason(r: SuggestedReader, language: BotLanguage): String {
    val pt = language == BotLanguage.PT
    val parts = mutableListOf<String>()
    if (r.sharedBooks > 0 && r.topTitle != null) {
        val more = r.sharedBooks - 1
        parts += (if (pt) "Também adorou ${r.topTitle}" else "Also loved ${r.topTitle}") +
            if (more > 0) (if (pt) " e mais $more" else " and $more more") else ""
    }
    if (r.mutualFriends > 0 && r.mutualNames.isNotEmpty()) {
        val names = r.mutualNames.joinToString(if (pt) " e " else " and ")
        val more = r.mutualFriends - r.mutualNames.size
        parts += (if (pt) "Amigos em comum: $names" else "Friend of $names") +
            if (more > 0) (if (pt) " e mais $more" else ", and $more more") else ""
    }
    return parts.joinToString(" · ")
}
