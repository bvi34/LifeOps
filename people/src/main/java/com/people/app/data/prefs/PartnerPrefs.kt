package com.people.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Who *this* install is on the partner seam.
 *
 * One id and one name, minted once and then never changed, because both are load-bearing: the id is
 * how a partner addresses this instance in every envelope after the pairing, and changing it would
 * silently orphan every link anybody has already made to us. It is minted lazily rather than at
 * install so a household that never pairs with anybody never acquires an identifier at all.
 *
 * The file is named `people_partner_prefs` — the `people` prefix is what keeps it inside
 * [com.people.app.backup.PeopleBackupContributor]'s sweep, so a restored device comes back as the
 * same instance its partners are already writing to rather than as a stranger nobody is paired with.
 */
class PartnerPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** This install's identity on the seam. Minted on first use and stable for ever after. */
    val instanceId: String
        get() = prefs.getString(KEY_INSTANCE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_INSTANCE_ID, it).apply()
        }

    /**
     * The identity we already have, or null — asked without minting one.
     *
     * The lazy mint above is deliberate, so anything that merely *reports* on the seam has to be
     * able to look without setting it up. A screen that read [instanceId] to say "not set up yet"
     * would have made that sentence false by asking the question.
     */
    val existingInstanceId: String? get() = prefs.getString(KEY_INSTANCE_ID, null)

    /**
     * The name a partner sees on our code and our envelopes.
     *
     * Editable, unlike the id — this one is only ever shown to a human, so getting it wrong is a
     * cosmetic problem rather than a structural one. It defaults to empty and the pairing screen
     * asks for it, because "Pair with Pixel 7" is not a person and a household directory of device
     * names would be a poor thing to hand somebody.
     */
    var displayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_DISPLAY_NAME, value.trim()).apply()

    /** When a partner round last ran at all, for the status line. */
    var lastRoundAt: Long
        get() = prefs.getLong(KEY_LAST_ROUND, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_ROUND, value).apply()

    companion object {
        const val FILE_NAME = "people_partner_prefs"
        private const val KEY_INSTANCE_ID = "instance_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_LAST_ROUND = "last_round_at"
    }
}
