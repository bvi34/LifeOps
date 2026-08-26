package com.logistics.app.logic

import com.logistics.app.data.model.PantryItem

/**
 * Framework-free shelf filtering. A pantry line that has been used up is kept — not deleted — so its
 * ledger and its low-stock alert survive ("we're out of milk" is a fact worth keeping). But a shelf
 * full of zeroes is noise on the screens that are about *what you have*, so Pantry and Log meal hide
 * empty lines by default and offer them back behind a toggle. Kept pure so the rule reads the same
 * on both screens and is JVM-tested.
 */
object PantryFilters {

    /** Nothing left on the shelf. Uses `<= 0` rather than `== 0` so a drifted float can't hide. */
    fun isEmpty(item: PantryItem): Boolean = item.quantity <= 0.0

    /**
     * Drop the used-up lines when [hide] is on. Ids in [keep] always survive the filter — Log meal
     * uses it so an item you've already ticked (or one a recipe auto-selected) can't vanish out from
     * under the selection, the same way the search box never hides what you marked.
     */
    fun hideEmpty(items: List<PantryItem>, hide: Boolean, keep: Set<String> = emptySet()): List<PantryItem> =
        if (!hide) items else items.filter { !isEmpty(it) || it.id in keep }

    /** How many lines [hideEmpty] would drop — what the toggle's label counts. */
    fun emptyCount(items: List<PantryItem>): Int = items.count { isEmpty(it) }
}
