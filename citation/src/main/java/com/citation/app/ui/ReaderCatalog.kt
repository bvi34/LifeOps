package com.citation.app.ui

import androidx.lifecycle.viewModelScope
import com.citation.app.data.AcquireResult
import com.citation.app.data.acquire
import com.citation.app.data.addCatalog
import com.citation.app.data.catalogImage
import com.citation.app.data.deleteCatalog
import com.citation.app.data.opds.OpdsClient
import com.citation.app.data.openCatalog
import com.citation.app.data.searchCatalog
import com.citation.app.data.seedCatalogsIfEmpty
import com.citation.app.data.setCatalogCredentials
import com.citation.core.opds.CatalogPage
import com.citation.core.opds.CatalogSource
import com.citation.core.opds.OpdsEntry
import com.citation.core.opds.OpdsFeed
import kotlinx.coroutines.launch

/**
 * Browsing an OPDS catalog and downloading from it.
 */

/** Make sure the presets exist before the catalogs list is first shown. */
internal fun ReaderViewModel.ensureCatalogs() {
    viewModelScope.launch { repository.seedCatalogsIfEmpty() }
}

internal fun ReaderViewModel.openCatalogs() {
    _catalogsOpen.value = true
    ensureCatalogs()
}

internal fun ReaderViewModel.closeCatalogs() {
    closeCatalog()
    _catalogsOpen.value = false
}

internal fun ReaderViewModel.openCatalog(source: CatalogSource) {
    catalogStack.clear()
    loadCatalog(source, null, push = false)
}

/** Follow a link within the catalog currently open. */
internal fun ReaderViewModel.followCatalogLink(url: String) {
    val current = _catalogPage.value ?: return
    loadCatalog(current.source, url, push = true)
}

internal fun ReaderViewModel.retryCatalog() {
    val current = _catalogPage.value
    if (current != null) loadCatalog(current.source, current.url, push = false)
}

internal fun ReaderViewModel.loadCatalog(source: CatalogSource, url: String?, push: Boolean) {
    viewModelScope.launch {
        _catalogLoading.value = true
        _catalogError.value = null
        val previous = _catalogPage.value
        when (val result = repository.openCatalog(source, url)) {
            is OpdsClient.Result.Success -> {
                if (push && previous != null) catalogStack.addLast(previous)
                _catalogPage.value = result.value
            }
            else -> {
                _catalogError.value = catalogFailure(result)
                // Keep whatever page was already up, so a failed step does not empty the screen.
                if (previous == null) _catalogPage.value = CatalogPage(source, url ?: source.rootUrl, emptyFeed(source, url))
            }
        }
        _catalogLoading.value = false
    }
}

internal fun ReaderViewModel.searchCatalog(terms: String) {
    val current = _catalogPage.value ?: return
    if (terms.isBlank()) return
    viewModelScope.launch {
        _catalogLoading.value = true
        _catalogError.value = null
        when (val result = repository.searchCatalog(current, terms)) {
            is OpdsClient.Result.Success -> {
                catalogStack.addLast(current)
                _catalogPage.value = result.value
            }
            else -> _catalogError.value = catalogFailure(result)
        }
        _catalogLoading.value = false
    }
}

/** Step back up the catalog. Returns false when there is nowhere left to go. */
internal fun ReaderViewModel.catalogBack(): Boolean {
    val previous = catalogStack.removeLastOrNull() ?: return false
    _catalogPage.value = previous
    _catalogError.value = null
    return true
}

internal fun ReaderViewModel.closeCatalog() {
    catalogStack.clear()
    _catalogPage.value = null
    _catalogError.value = null
    catalogThumbnails.clear()
    catalogThumbnailMisses.clear()
}

internal fun ReaderViewModel.emptyFeed(source: CatalogSource, url: String?) =
    OpdsFeed(title = source.name, id = null, links = emptyList(), entries = emptyList(), url = url ?: source.rootUrl)

internal fun ReaderViewModel.catalogFailure(result: OpdsClient.Result<*>): String = when (result) {
    is OpdsClient.Result.Unauthorized -> "This catalog needs a sign-in. Add one in its settings."
    is OpdsClient.Result.NotACatalog ->
        if (result.looksLikeHtml) "That address is a web page, not a catalog. Try adding /opds to it."
        else "The server didn’t answer with a catalog."
    is OpdsClient.Result.Unreachable -> "Couldn’t reach it: ${result.message}"
    is OpdsClient.Result.HttpError -> "The server said ${result.code}."
    is OpdsClient.Result.Unsupported -> "This catalog doesn’t offer that."
    is OpdsClient.Result.Success -> ""
}

internal suspend fun ReaderViewModel.catalogThumbnail(url: String): ByteArray? {
    catalogThumbnails[url]?.let { return it }
    if (url in catalogThumbnailMisses) return null
    val source = _catalogPage.value?.source ?: return null
    val bytes = repository.catalogImage(source, url)
    if (bytes == null) catalogThumbnailMisses.add(url) else catalogThumbnails[url] = bytes
    return bytes
}

/** Download a catalog entry into the library. */
internal fun ReaderViewModel.acquire(entry: OpdsEntry) {
    val source = _catalogPage.value?.source ?: return
    val id = entry.id ?: entry.title
    if (id in _acquiring.value) return
    viewModelScope.launch {
        _acquiring.value = _acquiring.value + id
        val result = repository.acquire(source, entry)
        _status.value = when (result) {
            is AcquireResult.Added -> "Added “${result.title}” to your library."
            is AcquireResult.AlreadyHave -> "“${result.title}” is already in your library."
            is AcquireResult.UnsupportedFormat ->
                "Citation can’t read ${result.label} files yet."
            is AcquireResult.Failed -> "Couldn’t download it: ${result.reason}"
        }
        _acquiring.value = _acquiring.value - id
    }
}

internal fun ReaderViewModel.addCatalog(name: String, url: String, username: String, password: String) {
    viewModelScope.launch {
        val added = repository.addCatalog(name, url, username.takeIf { it.isNotBlank() }, password)
        _status.value = "Added “${added.name}”."
    }
}

internal fun ReaderViewModel.deleteCatalog(id: String) {
    viewModelScope.launch { repository.deleteCatalog(id) }
}

internal fun ReaderViewModel.setCatalogCredentials(id: String, username: String, password: String) {
    viewModelScope.launch { repository.setCatalogCredentials(id, username, password) }
}
