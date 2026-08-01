package com.lifeops.app

import androidx.core.content.FileProvider

/**
 * A distinctly-named [FileProvider] subclass so LifeOps' provider and Citation's provider are two
 * separate `<provider>` nodes when both library manifests merge into the Operations Sandbox app.
 * The manifest merger keys providers by class name, not authority, so sharing the base
 * `androidx.core.content.FileProvider` class would collide; distinct subclasses do not.
 *
 * Behaviour is identical to the base class — the authority + `@xml` paths come from the manifest.
 * [androidx.core.content.FileProvider.getUriForFile] resolves by authority, so call sites are
 * unaffected.
 */
class LifeOpsFileProvider : FileProvider()
