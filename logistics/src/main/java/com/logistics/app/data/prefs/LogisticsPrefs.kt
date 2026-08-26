package com.logistics.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Logistics' display settings. Nothing here is a fact about your shelves — it's how the shelves are
 * *shown* — so it lives beside the database rather than in it (the same split Health draws between a
 * reading and the unit you read it in).
 *
 * Today that's one setting: whether used-up pantry lines are hidden. It defaults to on, because a
 * pantry is worth keeping zeroed-out rows in (the ledger and the low-stock alert outlive the stock)
 * but is not worth reading them in — "what's on my shelf" and "what did I cook with" are both
 * questions about what's actually there.
 *
 * The file is named `logistics_prefs` so the sandbox's per-app prefs isolation — every contributor
 * touches only files matching its own prefix — keeps working.
 */
class LogisticsPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Hide pantry lines with nothing left on them. On by default; shared by Pantry and Log meal. */
    var hideEmptyItems: Boolean
        get() = prefs.getBoolean(KEY_HIDE_EMPTY_ITEMS, true)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_EMPTY_ITEMS, value).apply()

    fun observeHideEmptyItems(): Flow<Boolean> = observe(KEY_HIDE_EMPTY_ITEMS) { hideEmptyItems }

    /** Emits on every change to [key], starting with the value at collection time. */
    private fun <T> observe(key: String, read: () -> T): Flow<T> = callbackFlow {
        trySend(read())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == key) trySend(read())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    companion object {
        const val FILE_NAME = "logistics_prefs"
        private const val KEY_HIDE_EMPTY_ITEMS = "hide_empty_items"
    }
}
