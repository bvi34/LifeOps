package com.citation.app.ui



/**
 * Finding notes again: free-text search, the tag facet, and the Markdown export.
 */

internal fun ReaderViewModel.setQuery(text: String) { _query.value = text }

/** Toggle a tag filter: tapping the active tag clears it, tapping another switches to it. */
internal fun ReaderViewModel.toggleTag(tag: String) {
    _activeTag.value = if (_activeTag.value == tag) null else tag
}
