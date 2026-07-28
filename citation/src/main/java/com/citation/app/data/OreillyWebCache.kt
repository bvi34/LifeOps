package com.citation.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.webkit.WebView
import java.io.File

/**
 * The Android side of O'Reilly's **warm page cache** — the WebView's own HTTP cache of pages you've
 * already opened. The policy (TTL, disposable, reclaimable) lives in
 * [com.citation.core.oreilly.OreillyCachePolicy]; this is the thin glue that measures the cache,
 * clears it, and reports connectivity so the reader can serve a page you've seen when you're briefly
 * offline.
 *
 * There is **no permanent copy of licensed content** here: the WebView cache lives under `cacheDir`
 * (OS-evictable), is cleared wholesale by [clear], and is never promoted into the sovereign store.
 */
object OreillyWebCache {

    /**
     * Best-effort footprint of the WebView's HTTP cache under `cacheDir`. The exact directory name is
     * WebView-version-specific, so we sum any cache subdirectory that looks like WebView's — returning
     * 0 when there's nothing to show rather than guessing. Visibility only; nothing is deleted here.
     */
    fun sizeBytes(context: Context): Long {
        val cacheRoot = context.cacheDir ?: return 0L
        val candidates = cacheRoot.listFiles { f ->
            f.isDirectory && f.name.contains("webview", ignoreCase = true)
        }.orEmpty()
        return candidates.sumOf { dirBytes(it) }
    }

    /**
     * Clear the WebView's HTTP cache (the warm pages). Uses the WebView API — the reliable, supported
     * way — so it must run on the main thread; the Settings/reader callers already are. Your notes and
     * saved position are untouched: they live in the sovereign store, not this cache.
     */
    fun clear(context: Context) {
        WebView(context.applicationContext).clearCache(true)
    }

    /** True when there's a validated internet-capable network — the reader uses this to pick a cache mode. */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun dirBytes(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
