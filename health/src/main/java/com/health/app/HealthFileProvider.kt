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
 * It grants read access to one thing only — the insurance card PDF Health writes into
 * `cacheDir/exports` when somebody asks to share one. The card *photographs* live in `filesDir` and
 * are deliberately not exposed here: they are a record, not an export, and the only way a card leaves
 * the app is the PDF the user explicitly asked for.
 */
class HealthFileProvider : FileProvider()
