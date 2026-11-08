package com.mcosta.mybooks.reader

import android.graphics.Color
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import com.mcosta.mybooks.domain.Quote
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.publication.Locator

/** The decoration group of highlights in the EPUB reader. */
const val HIGHLIGHTS = "highlights"

/** Highlights are a warm yellow, readable in light, sepia and dark. */
private val HIGHLIGHT_TINT = Color.rgb(242, 193, 78)

/** The text-selection menu gets "Highlight", which calls [onHighlight] and closes the menu. */
class HighlightActionMode(private val onHighlight: () -> Unit) : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        menu.add(Menu.NONE, ITEM, 0, "Highlight")
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (item.itemId != ITEM) return false
        onHighlight()
        mode.finish()
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) = Unit

    private companion object {
        const val ITEM = 0x4D42
    }
}

/** One highlight decoration per quote with a readable position; its id ("q<quote id>") leads back to the quote. */
fun highlightDecorations(highlights: List<Quote>): List<Decoration> = highlights.mapNotNull { q ->
    val locator = q.position?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() } ?: return@mapNotNull null
    Decoration(id = "q${q.id}", locator = locator, style = Decoration.Style.Highlight(tint = HIGHLIGHT_TINT))
}

/** The quote id of a highlight decoration, or null for another kind. */
fun quoteIdOf(decorationId: String): Long? = decorationId.takeIf { it.startsWith("q") }?.drop(1)?.toLongOrNull()
