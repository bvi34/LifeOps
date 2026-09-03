package com.repository.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.repository.app.logic.Drive

/**
 * Repository's only "where was I" state: which drive the household uses, and where on it.
 *
 * None of this is content, which is why it is preferences rather than a table — and none of it is
 * carried by the backup either, deliberately. What is remembered here is a URI granted by a document
 * provider **on this device, to this install**. Restore it onto a new phone and it names a folder
 * nothing has permission to open; the household would be looking at "OneDrive · Project docs" and
 * getting a failure. Forgetting is the honest behaviour: the first export after a restore asks for
 * the folder again, once, and then remembers it again.
 *
 * The file is named `repository_prefs` so the sandbox's per-app prefs isolation — each contributor
 * touches only files matching its own prefix — keeps working.
 */
class RepositoryPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** The drive the import screen opens on, because it is almost always the same one. */
    var lastDrive: Drive?
        get() = Drive.fromKey(prefs.getString(KEY_LAST_DRIVE, null))
        set(value) = prefs.edit().putString(KEY_LAST_DRIVE, value?.key).apply()

    /**
     * Where the picker was last pointed at on a drive, used as its starting point next time.
     *
     * Per drive rather than one value: somebody who keeps statements on Drive and project docs on
     * OneDrive is not helped by the picker opening at whichever they touched most recently.
     */
    fun lastPlace(drive: Drive): String? = prefs.getString(KEY_PLACE + drive.key, null)

    fun rememberPlace(drive: Drive, uri: String?) {
        prefs.edit().putString(KEY_PLACE + drive.key, uri).apply()
    }

    /** The folder on a drive that documents were last saved into, and what it is called there. */
    fun lastFolder(drive: Drive): String? = prefs.getString(KEY_FOLDER + drive.key, null)

    fun lastFolderLabel(drive: Drive): String? = prefs.getString(KEY_FOLDER_LABEL + drive.key, null)

    fun rememberFolder(drive: Drive, uri: String?, label: String?) {
        prefs.edit()
            .putString(KEY_FOLDER + drive.key, uri)
            .putString(KEY_FOLDER_LABEL + drive.key, label)
            .apply()
    }

    fun forgetFolder(drive: Drive) = rememberFolder(drive, null, null)

    companion object {
        const val FILE_NAME = "repository_prefs"
        private const val KEY_LAST_DRIVE = "last_drive"
        private const val KEY_PLACE = "last_place_"
        private const val KEY_FOLDER = "last_folder_"
        private const val KEY_FOLDER_LABEL = "last_folder_label_"
    }
}
