package com.repository.app.logic

/**
 * The drives a household keeps its paperwork on, and how this app recognises one.
 *
 * ### There is no Google Drive API here, and there is no OneDrive API here
 *
 * That is the decision this whole feature rests on, so it is worth stating before anything else.
 * Both drives already publish themselves to Android as **document providers** — the same mechanism
 * the system Files app browses them through. Asking for a file from one is a picker with a starting
 * point, not a network client.
 *
 * The alternative was an OAuth flow per drive, two SDKs, two sets of API keys shipped in the apk,
 * a token store, a refresh path, a sync loop and a permission the module has never needed. All of
 * that to end up holding the same bytes the picker hands over for free. It would also break the one
 * promise the app makes about itself: **Repository stores documents, it does not read them**. A
 * client that can list your whole Drive is a client that has read a great deal more than the four
 * files you asked for.
 *
 * So a drive here is an *authority* — a string on the content URI the picker returns — and this
 * enum is the short list of the ones worth naming. Everything else still works; it is simply not
 * recognised by name, which costs a label and nothing else.
 *
 * ### Targeted, both ways
 *
 * The point is not "sync my Drive". It is *these four documents, now*: you know which project docs
 * you want on the shelf, you pick them, they are copied in, and the drive is not consulted again.
 * Going the other way is the same shape — this document, into that folder, once. A copy that goes
 * stale is a copy the household can see is a copy; a background sync that goes wrong is a document
 * that quietly changed.
 */
enum class Drive(
    val key: String,
    val label: String,
    /**
     * The content authorities this drive's provider publishes under.
     *
     * More than one because apps rename providers and keep the old one working — Google's legacy
     * authority is still what an older Play Services answers on, and a household on it should not
     * be told its Drive is "somewhere else".
     */
    val authorities: List<String>
) {
    GOOGLE_DRIVE(
        key = "google_drive",
        label = "Google Drive",
        authorities = listOf(
            "com.google.android.apps.docs.storage",
            "com.google.android.apps.docs.storage.legacy"
        )
    ),
    ONEDRIVE(
        key = "onedrive",
        label = "OneDrive",
        authorities = listOf(
            "com.microsoft.skydrive.content.StorageAccessProvider",
            "com.microsoft.skydrive.content.external"
        )
    ),
    DROPBOX(
        key = "dropbox",
        label = "Dropbox",
        authorities = listOf("com.dropbox.product.android.dbapp.document_provider.documents")
    ),

    /**
     * The phone itself — downloads, internal storage, an SD card.
     *
     * Named alongside the clouds rather than treated as the absence of one, because "I already
     * downloaded them" is how half of these imports actually start.
     */
    DEVICE(
        key = "device",
        label = "This device",
        authorities = listOf(
            "com.android.externalstorage.documents",
            "com.android.providers.downloads.documents",
            "com.android.providers.media.documents"
        )
    );

    val isCloud: Boolean get() = this != DEVICE

    companion object {
        fun fromKey(key: String?): Drive? = entries.firstOrNull { it.key == key }
    }
}

object Drives {

    /** The drives offered as a starting point, clouds first — that is what people come here for. */
    val OFFERED: List<Drive> = listOf(Drive.GOOGLE_DRIVE, Drive.ONEDRIVE, Drive.DROPBOX, Drive.DEVICE)

    /**
     * Which drive a picked URI actually came from, or null for a provider nobody here has named.
     *
     * Worth being precise about *actually*: the drive chips on the import screen are a starting
     * point for the picker and nothing more. Android's picker lets you walk anywhere from wherever
     * it opens, and it should — somebody who meant Drive and found the file in Downloads has found
     * their file. So the app never claims a file came from the chip that was selected; it reads the
     * URI and says what is true.
     */
    fun of(uri: String?): Drive? {
        val authority = authorityOf(uri) ?: return null
        return Drive.entries.firstOrNull { drive ->
            drive.authorities.any { it.equals(authority, ignoreCase = true) }
        }
    }

    /** What to call where a file came from: the drive's name, or the bare authority if unknown. */
    fun label(uri: String?): String? = of(uri)?.label ?: authorityOf(uri)

    /**
     * The authority out of a `content://` URI, by hand.
     *
     * String work rather than `android.net.Uri`, so this file stays Android-free and unit-tested
     * with no emulator — the same rule the rest of `logic/` follows. A URI that is not a hierarchical
     * one, or has no authority at all, is null rather than an empty string: "came from nowhere" and
     * "came from a provider with no name" are the same answer here.
     */
    fun authorityOf(uri: String?): String? {
        val value = uri?.trim().orEmpty()
        val scheme = value.substringBefore("://", missingDelimiterValue = "")
        if (scheme.isEmpty() || !value.startsWith("$scheme://")) return null
        return value.removePrefix("$scheme://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .takeIf { it.isNotBlank() }
    }
}
