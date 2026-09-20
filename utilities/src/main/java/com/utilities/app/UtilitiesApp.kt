package com.utilities.app

import android.app.Application
import android.content.Context
import com.utilities.app.data.LexiconStore
import com.utilities.app.data.LookStore

/**
 * Utilities' runtime container, mirroring the other hosted apps: the Operations Sandbox
 * [Application] calls [install] once, and the screens resolve it with [get].
 *
 * ## What install does, which is deliberately almost nothing
 *
 * It opens the two stores. That is it — no worker, no service started, no notification channel
 * created, no listener registered. This app has two components the *operating system* starts on its
 * own (the keyboard, and the receivers that get texts when it is the default messenger), and every
 * one of those resolves its own store on the way in. An `install` that pre-warmed them would be
 * doing work on every launch of the suite for the benefit of a keyboard that may never be selected.
 *
 * It is called at start-up anyway, for one reason: the stores are singletons, and a restore needs
 * something to tell that the file underneath it changed (see `UtilitiesBackupContributor`). Opening
 * them here costs one preferences read and one small file read.
 */
class UtilitiesApp private constructor(app: Application) {

    val looks: LookStore = LookStore.get(app)

    val lexicon: LexiconStore = LexiconStore.get(app)

    companion object {

        @Volatile
        private var instance: UtilitiesApp? = null

        fun install(app: Application): UtilitiesApp =
            instance ?: synchronized(this) {
                instance ?: UtilitiesApp(app).also { instance = it }
            }

        fun get(context: Context): UtilitiesApp =
            instance ?: synchronized(this) {
                instance ?: UtilitiesApp(context.applicationContext as Application).also { instance = it }
            }
    }
}
