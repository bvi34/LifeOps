package com.utilities.app.messages

import android.content.Context

/**
 * The handful of choices the messaging takeover has, in a plain preferences file.
 *
 * All of them are about **picture messages**, because that is the only part of messaging where the
 * right behaviour depends on what the household is paying for. A text arrives whether anybody likes
 * it or not; an MMS has to be *fetched*, over cellular data, possibly while roaming, at a size the
 * sender chose.
 *
 * The file is named `utilities_messages` so the backup contributor collects it — nothing in here is
 * a secret, and all of it is the kind of setting somebody would be annoyed to set twice.
 */
class MessagePrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Fetch a picture message as soon as it is announced.
     *
     * On by default, because the alternative — every photograph arriving as a grey box with a
     * button — is a messaging app that feels broken, and because on any ordinary plan the data is
     * already paid for. Off is offered for a metered connection, and it is a real setting rather
     * than a token one: with it off nothing is fetched until somebody taps, and the network is told
     * the message is *deferred* rather than left to re-push it.
     */
    var autoDownload: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DOWNLOAD, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD, value).apply()

    /**
     * …including while roaming.
     *
     * Off by default, and this is the one default worth defending: an automatic download abroad is
     * a charge nobody asked for, cannot see coming, and finds out about a month later. A message
     * that arrives while roaming is stored as a placeholder and fetched the moment somebody taps it,
     * which costs one tap and can cost nothing else.
     */
    var autoDownloadRoaming: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DOWNLOAD_ROAMING, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD_ROAMING, value).apply()

    /**
     * Send a group text as one picture message rather than as several one-to-one texts.
     *
     * On by default. Off, everybody gets a text with no idea the others were written to; on, they
     * see a conversation. It is a real choice — the first costs nothing and the second counts
     * against a picture-message allowance — which is why it is offered rather than assumed.
     */
    var groupAsMms: Boolean
        get() = prefs.getBoolean(KEY_GROUP_MMS, true)
        set(value) = prefs.edit().putBoolean(KEY_GROUP_MMS, value).apply()

    /**
     * Ask the network to confirm a picture message arrived.
     *
     * Off by default, and not because it is expensive. A delivery report tells the *sender*
     * something; a read report tells the *recipient's phone* to tell somebody else what they have
     * looked at, which is a thing to opt into rather than out of. This app never asks for the second
     * one at all — see `PduEncoder`.
     */
    var deliveryReports: Boolean
        get() = prefs.getBoolean(KEY_DELIVERY_REPORTS, false)
        set(value) = prefs.edit().putBoolean(KEY_DELIVERY_REPORTS, value).apply()

    companion object {
        /** Carried by the archive: the prefix is what decides, and this one starts with it. */
        const val FILE_NAME = "utilities_messages"

        private const val KEY_AUTO_DOWNLOAD = "auto_download"
        private const val KEY_AUTO_DOWNLOAD_ROAMING = "auto_download_roaming"
        private const val KEY_GROUP_MMS = "group_mms"
        private const val KEY_DELIVERY_REPORTS = "delivery_reports"
    }
}
