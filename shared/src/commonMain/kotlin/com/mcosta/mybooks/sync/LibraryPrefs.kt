package com.mcosta.mybooks.sync

import com.mcosta.mybooks.data.PinnedTags
import com.mcosta.mybooks.data.suggestions.HiddenSuggestions
import com.mcosta.mybooks.physical.BOOKCASE_PREFIX
import com.mcosta.mybooks.physical.BookcaseStore
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
    private val bookcases: BookcaseStore? = null,
) : SyncedPrefs {
    override val keys: Collection<String>
        get() = listOf(PinnedTags.KEY, HiddenSuggestions.KEY) + shares?.exclusionKeys().orEmpty() + bookcases?.keys.orEmpty()

    override fun get(key: String): String? = when (key) {
        PinnedTags.KEY -> pinned.stored()
        HiddenSuggestions.KEY -> hidden.stored()
        else -> when {
            isShareExclusionKey(key) -> shares?.exclusionValue(key)
            key.startsWith(BOOKCASE_PREFIX) -> bookcases?.value(key)
            else -> null
        }
    }

    override fun set(key: String, value: String?) {
        when (key) {
            PinnedTags.KEY -> pinned.pulled(value)
            HiddenSuggestions.KEY -> hidden.pulled(value)
            else -> when {
                isShareExclusionKey(key) -> shares?.storeExclusion(key, value)
                key.startsWith(BOOKCASE_PREFIX) -> bookcases?.pulled(key, value)
            }
        }
    }
}
