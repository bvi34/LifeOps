package com.operations.backupkit

import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The archive mechanics: turn a set of [BackupContributor]s into a single `.zip`, and drive a
 * restore back out of one. Pure java.io/java.util.zip so it runs and unit-tests on a plain JVM.
 *
 * ## Archive layout
 * ```
 * manifest.json           ← table of contents (written last, read first)
 * lifeops/data.json       ← one directory per app, keyed by AppId.key
 * citation/citation.db
 * citation/sovereign/…
 * ```
 * The manifest is authoritative: [restore] walks the manifest's app list, not the raw zip entries,
 * so a partially-written archive can't be half-applied. Unknown app keys (an archive from a newer
 * sandbox) are skipped rather than failing the whole restore.
 */
object BackupEngine {

    const val MANIFEST_ENTRY = "manifest.json"

    /**
     * Write [contributors]' data into [out] as a zip. Payloads stream in first while their entry
     * names are recorded; the manifest is written last so it always reflects what actually landed.
     * [out] is closed when this returns.
     */
    fun backup(contributors: List<BackupContributor>, sandboxVersion: String, out: OutputStream) {
        val appEntries = mutableListOf<AppEntry>()
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            for (contributor in contributors) {
                val written = mutableListOf<String>()
                val sink = object : BackupSink {
                    override fun entry(relativePath: String): OutputStream {
                        val normalized = relativePath.trimStart('/')
                        written += normalized
                        zip.putNextEntry(ZipEntry("${contributor.appId.key}/$normalized"))
                        return ZipEntryStream(zip)
                    }
                }
                contributor.backup(sink)
                appEntries += AppEntry(
                    appId = contributor.appId.key,
                    displayName = contributor.displayName,
                    dataVersion = contributor.dataVersion,
                    entries = written
                )
            }
            val manifest = BackupManifest(
                createdAt = System.currentTimeMillis(),
                sandboxVersion = sandboxVersion,
                apps = appEntries
            )
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(BackupCodec.toJson(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    /**
     * Read only the manifest out of [zipIn] without extracting any payload — used to show the user
     * what's in an archive before they commit to restoring it. Returns null if there's no valid
     * manifest. [zipIn] is closed when this returns.
     */
    fun readManifest(zipIn: InputStream): BackupManifest? {
        ZipInputStream(zipIn).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == MANIFEST_ENTRY) {
                    return BackupCodec.fromJson(zip.readBytes().toString(Charsets.UTF_8))
                }
                entry = zip.nextEntry
            }
        }
        return null
    }

    /**
     * Restore selected [contributors] from [zipIn]. The archive is first fully extracted under
     * [workDir] (so large owned files never have to sit in memory), then each contributor named in
     * the manifest *and* present in [contributors] is handed a source over its own directory.
     *
     * Returns the archive's manifest. [zipIn] is closed when this returns; [workDir] is the caller's
     * to clean up.
     */
    fun restore(contributors: List<BackupContributor>, workDir: File, zipIn: InputStream): BackupManifest {
        val index = extractTo(workDir, zipIn)
        val manifestFile = index[MANIFEST_ENTRY]
            ?: throw IllegalArgumentException("Archive has no $MANIFEST_ENTRY")
        val manifest = BackupCodec.fromJson(manifestFile.readText())
            ?: throw IllegalArgumentException("Archive manifest is malformed")

        val byKey = contributors.associateBy { it.appId.key }
        for (app in manifest.apps) {
            val contributor = byKey[app.appId] ?: continue // no contributor for this app → skip
            val prefix = "${app.appId}/"
            val source = object : BackupSource {
                override fun list(): List<String> =
                    index.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }

                override fun open(relativePath: String): InputStream? =
                    index["$prefix${relativePath.trimStart('/')}"]?.inputStream()
            }
            contributor.restore(source)
        }
        return manifest
    }

    /**
     * Extract every zip entry under [workDir], preserving relative paths, and return an index of
     * entry-name → extracted file. Guards against zip-slip: an entry that would escape [workDir]
     * (via `../`) is rejected outright.
     */
    private fun extractTo(workDir: File, zipIn: InputStream): Map<String, File> {
        workDir.mkdirs()
        val root = workDir.canonicalFile
        val index = LinkedHashMap<String, File>()
        ZipInputStream(zipIn).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val dest = File(root, entry.name).canonicalFile
                    if (!dest.path.startsWith(root.path + File.separator) && dest != root) {
                        throw SecurityException("Archive entry escapes work dir: ${entry.name}")
                    }
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { zip.copyTo(it) }
                    index[entry.name] = dest
                }
                entry = zip.nextEntry
            }
        }
        return index
    }

    /**
     * A per-entry OutputStream handed to contributors. Writes go straight to the shared
     * [ZipOutputStream]; closing finalizes just this entry, never the whole archive.
     */
    private class ZipEntryStream(private val zip: ZipOutputStream) : OutputStream() {
        private var closed = false
        override fun write(b: Int) = zip.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = zip.write(b, off, len)
        override fun flush() = zip.flush()
        override fun close() {
            if (!closed) {
                closed = true
                zip.closeEntry()
            }
        }
    }
}
