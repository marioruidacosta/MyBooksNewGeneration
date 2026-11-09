package com.mcosta.mybooks.sync

import com.mcosta.mybooks.data.PinnedTags
import com.mcosta.mybooks.data.suggestions.HiddenSuggestions

/**
 * The preferences that sync (pinned tags, "Not interested"), read and written through the objects that hold them: a
 * pulled value shows on screen at once, and isn't reported as a change (it would be pushed back).
 */
class LibraryPrefs(private val pinned: PinnedTags, private val hidden: HiddenSuggestions) : SyncedPrefs {
    override val keys: Collection<String> = listOf(PinnedTags.KEY, HiddenSuggestions.KEY)

    override fun get(key: String): String? = when (key) {
        PinnedTags.KEY -> pinned.stored()
        HiddenSuggestions.KEY -> hidden.stored()
        else -> null
    }

    override fun set(key: String, value: String?) {
        when (key) {
            PinnedTags.KEY -> pinned.pulled(value)
            HiddenSuggestions.KEY -> hidden.pulled(value)
        }
    }
}
