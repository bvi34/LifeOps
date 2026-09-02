package com.repository.app

import androidx.core.content.FileProvider

/**
 * A distinctly-named [FileProvider] subclass so this provider and the other apps' are separate
 * `<provider>` nodes when the library manifests merge into the Operations Sandbox app. The manifest
 * merger keys providers by class name, not authority, so sharing the base class would collide.
 *
 * It exposes `cacheDir/exports` and nothing else — see `res/xml/repository_file_paths.xml`. The
 * documents themselves are never behind a URI another app could hold.
 */
class RepositoryFileProvider : FileProvider()
