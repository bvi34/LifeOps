package com.operations.vaultkit

import com.operations.backupkit.AppId

/**
 * Who a mirrored secret belongs to.
 *
 * This used to be an [AppId] and could not stay one. The suite's credentials are not all held by
 * *hosted apps*: the Operations Sandbox — the container the eleven apps live inside — holds one
 * itself, the GitHub token its updater checks releases with, and it is the same kind of thing for
 * exactly the same reason. It sits in `EncryptedSharedPreferences` behind a Keystore key, it is
 * deliberately kept out of the archive, and so it dies on a restore. That is the failure this whole
 * app exists to fix, and it was happening in the shell that hosts it.
 *
 * The obvious fix — add a `SANDBOX` entry to [AppId] — is wrong, and the test that makes it wrong is
 * a good one: `SuiteAppsTest` asserts that every [AppId] has a tile on the home screen. The shell is
 * not an app on its own home screen. So the owner of a secret is widened here instead, to *either* a
 * hosted app or the container, and [AppId] goes back to meaning what it says.
 *
 * ## Why the key matters more than the type
 *
 * [key] is what is written down: it is the first segment of a [SecretRef] and the value stored in
 * `VaultItem.managedBy`. It has to stay stable across versions for the same reason [AppId.key] does
 * — an archive written last year names its owners by string — so the set of keys is closed and
 * [fromKey] is the only way back from one.
 *
 * Equality is on [key] alone. Two owners with the same key are the same owner however they were
 * obtained, which is what makes this safe as a map key in [SecretSources].
 */
class SecretOwner private constructor(

    /** The stable string this owner is filed under. Matches [AppId.key] for a hosted app. */
    val key: String,

    /** What a person sees in the Secrets list: "Finance", "Operations Sandbox". */
    val displayName: String
) {

    /** True for the container itself rather than one of the apps it hosts. */
    val isShell: Boolean get() = key == SHELL_KEY

    /** The hosted app this owner is, or null for the shell. */
    val appId: AppId? get() = AppId.fromKey(key)

    override fun equals(other: Any?): Boolean = other is SecretOwner && other.key == key

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = key

    companion object {

        /**
         * The container's own key.
         *
         * `sandbox` rather than `app` or `shell` because it is the name the module already goes by
         * everywhere else in the suite — `com.operations.sandbox`, "the Operations Sandbox" — and a
         * filing name nobody recognises is a filing name somebody deletes.
         *
         * It must never collide with an [AppId.key]; `SecretOwnerTest` asserts that it does not, so
         * a future hosted app called "Sandbox" fails a test rather than silently adopting the
         * shell's credentials.
         */
        const val SHELL_KEY = "sandbox"

        /**
         * The Operations Sandbox itself: the updater's GitHub token, and anything else the container
         * comes to hold on its own behalf rather than on an app's.
         */
        val SHELL = SecretOwner(SHELL_KEY, "Operations Sandbox")

        /** The owner for a hosted app. */
        fun of(appId: AppId): SecretOwner = SecretOwner(appId.key, appId.defaultDisplayName)

        /** Every owner that can file a secret: the eleven apps and the shell. */
        val all: List<SecretOwner> get() = AppId.entries.map { of(it) } + SHELL

        /**
         * The owner [key] names, or null if nothing does.
         *
         * Null is the right answer rather than a fabricated owner: an item whose `managedBy` is a key
         * this build has never heard of came from a newer version of the suite, and the list shows
         * the raw key instead of inventing a name for it.
         */
        fun fromKey(key: String?): SecretOwner? = when {
            key == null -> null
            key == SHELL_KEY -> SHELL
            else -> AppId.fromKey(key)?.let { of(it) }
        }
    }
}
