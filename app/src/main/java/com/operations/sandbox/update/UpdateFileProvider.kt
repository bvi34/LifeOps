package com.operations.sandbox.update

import androidx.core.content.FileProvider

/**
 * The provider that hands the downloaded APK to the system installer.
 *
 * A distinctly-named subclass rather than `androidx.core.content.FileProvider` itself, for the same
 * reason LifeOps, Citation, Health and Repository each have one: every hosted app lives in this one
 * process, and two `<provider>` entries naming the same class is a merge the manifest merger has to
 * resolve. Distinct names keep them independent — the authority is distinct too.
 */
class UpdateFileProvider : FileProvider()
