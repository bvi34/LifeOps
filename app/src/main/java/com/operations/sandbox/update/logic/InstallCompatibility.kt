package com.operations.sandbox.update.logic

/**
 * Whether the phone will accept a downloaded APK as a replacement for the build that is running.
 *
 * Android identifies an app by its package name *and* the key it was signed with, and it will not
 * let a package be replaced by one signed differently — the alternative is that anyone who can put
 * a file on the phone can overwrite your banking app. What the user sees when that rule bites is
 * "App not installed as package conflicts with an existing package", which names neither signing
 * nor the fix, and arrives *after* the download, at the end of the one flow this app exists for.
 *
 * The two builds that collide here are the obvious pair: a local `./gradlew assembleDebug` build is
 * signed with the debug keystore, and every release is signed with the release keystore the
 * workflow decodes. They share a package name, so the first real release a developer build ever
 * offers itself is the one install that cannot succeed.
 *
 * Deciding this is one set comparison, kept here in plain Kotlin so it can be tested on the JVM;
 * reading the certificates out of a package is Android's job and stays in the updater.
 */
enum class InstallCompatibility {
    /** Same signer: Android installs it over the top and the app's data is untouched. */
    REPLACEABLE,

    /** Different signers: Android will refuse, and saying so first is the whole point. */
    SIGNATURE_CONFLICT,

    /**
     * One side could not be read. Nothing is claimed and nothing is blocked — the installer is
     * still the authority on this, and an updater that refused to install on its own uncertainty
     * would be worse than the confusing dialog it is trying to avoid.
     */
    UNKNOWN;

    companion object {
        /**
         * Compare the certificate fingerprints of the installed package with the APK's.
         *
         * An intersection rather than an equality: a package whose signing key has been rotated
         * carries its whole lineage, and Android accepts an APK signed by any certificate in it.
         * Case and surrounding space are normalised because these come from two different reads of
         * the platform's own formatting, and a fingerprint that differs only in case is the same
         * fingerprint.
         */
        fun of(installed: Set<String>, apk: Set<String>): InstallCompatibility {
            val left = installed.normalise()
            val right = apk.normalise()
            return when {
                left.isEmpty() || right.isEmpty() -> UNKNOWN
                left.intersect(right).isNotEmpty() -> REPLACEABLE
                else -> SIGNATURE_CONFLICT
            }
        }

        private fun Set<String>.normalise(): Set<String> =
            mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }.toSet()
    }
}
