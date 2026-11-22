package com.mcosta.mybooks.sync

import com.mcosta.mybooks.data.PinnedTags
import com.mcosta.mybooks.data.suggestions.HiddenSuggestions
import com.mcosta.mybooks.share.ShareTracker
import com.mcosta.mybooks.share.isShareExclusionKey

/**
 * The preferences that sync (pinned tags, "Not interested", "Don't share this book"), read and written through the objects that hold them: a
 * pulled value shows on screen at once, and isn't reported as a change (it would be pushed back).
 */
class LibraryPrefs(
    private val pinned: PinnedTags,
    private val hidden: HiddenSuggestions,
    private val shares: ShareTracker? = null,
) : SyncedPrefs {
    override val keys: Collection<String>
        get() = listOf(PinnedTags.KEY, HiddenSuggestions.KEY) + shares?.exclusionKeys().orEmpty()

    override fun get(key: String): String? = when (key) {
        PinnedTags.KEY -> pinned.stored()
        HiddenSuggestions.KEY -> hidden.stored()
        else -> if (isShareExclusionKey(key)) shares?.exclusionValue(key) else null
    }

    override fun set(key: String, value: String?) {
        when (key) {
            PinnedTags.KEY -> pinned.pulled(value)
            HiddenSuggestions.KEY -> hidden.pulled(value)
            else -> if (isShareExclusionKey(key)) shares?.storeExclusion(key, value)
        }
    }
}
