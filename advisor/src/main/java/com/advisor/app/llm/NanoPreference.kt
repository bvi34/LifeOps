package com.advisor.app.llm

import android.content.Context

/**
 * The model card's "Use Gemini Nano" switch. **Off** until the user turns it on: Advisor's promise is
 * that it runs the model you put on the phone, so handing questions to a system model is a choice the
 * user makes, not one an update makes for them.
 *
 * Kept out of the suite backup on purpose. Whether Nano exists is a fact about *this* phone; a
 * restore onto another one should start from the default and let its owner decide again.
 */
class NanoPreference(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(value) {
            prefs.edit().putBoolean(KEY, value).apply()
        }

    private companion object {
        const val FILE = "advisor_engine"
        const val KEY = "prefer_gemini_nano"
    }
}
