package com.health.app

import androidx.core.content.FileProvider

/**
 * A distinctly-named [FileProvider] subclass so Health's provider, Citation's and LifeOps' are three
 * separate `<provider>` nodes when the library manifests merge into the Operations Sandbox app. The
 * manifest merger keys providers by class name, not authority, so sharing the base
 * `androidx.core.content.FileProvider` class would collide; distinct subclasses do not. Citation hit
 * this first — see `CitationFileProvider` — and Health follows the pattern rather than rediscovering
 * it.
 *
 * Behaviour is identical to the base class: the authority and the `@xml` paths come from the
 * manifest. [androidx.core.content.FileProvider.getUriForFile] resolves by authority, so call sites
 * are unaffected.
 *
 * It grants read access to one directory only — `cacheDir/exports`, where Health writes a file at the
 * moment somebody asks to share it: the insurance card PDF, or a copy of a stored document.
 *
 * The records themselves are deliberately **not** exposed. Card photographs live in
 * `filesDir/insurance-cards` and the household's paperwork in `filesDir/documents`; widening the
 * provider to either would make every lab result in the house readable by anything that could guess
 * a URI. They are records, not exports, and the only way one leaves the app is a copy the user
 * explicitly asked for.
 */
class HealthFileProvider : FileProvider() {

    companion object {
        /**
         * Appended to the package name to form the authority. Matches `android:authorities` in the
         * manifest, and is named here rather than repeated at each call site — an authority that
         * drifts from the manifest fails at the moment somebody tries to share something, which is
         * the worst possible time to find out.
         */
        const val AUTHORITY_SUFFIX = ".health.fileprovider"

        fun authority(packageName: String): String = "$packageName$AUTHORITY_SUFFIX"
    }
}
