package com.operations.backupkit

import java.io.InputStream
import java.io.OutputStream

/**
 * How one hosted app hands its data to (and takes it back from) the sandbox. Implementations live in
 * the Android feature modules — they read Room databases and file stores — but the surface here is
 * framework-free: an app only ever sees streams and relative entry names, never the zip.
 *
 * Contract:
 *  - [backup] and [restore] run on a background thread; blocking IO is expected.
 *  - Entry names are relative to the app's own root; the engine namespaces them under `<appId>/`.
 *  - A contributor must be able to restore any archive it produced (round-trip its own format).
 */
interface BackupContributor {
    val appId: AppId
    val displayName: String get() = appId.defaultDisplayName

    /** The contributor's own data-format version, recorded in the manifest for restore-time checks. */
    val dataVersion: Int

    /** Write this app's payload by opening one [BackupSink.entry] per file. */
    fun backup(sink: BackupSink)

    /** Rebuild this app's state from the entries it previously wrote, served by [source]. */
    fun restore(source: BackupSource)
}

/** Write side handed to a contributor during backup. Open an entry, write it, close it, repeat. */
interface BackupSink {
    /**
     * Open an output stream for one entry at [relativePath] (relative to the app root). Write your
     * bytes, then **close the returned stream** to finalize the entry before opening the next one.
     * Closing this stream does not close the underlying archive.
     */
    fun entry(relativePath: String): OutputStream
}

/** Read side handed to a contributor during restore. */
interface BackupSource {
    /** The relative paths this app contributed, exactly as passed to [BackupSink.entry]. */
    fun list(): List<String>

    /** Open one entry for reading, or null if [relativePath] isn't present. Caller closes it. */
    fun open(relativePath: String): InputStream?
}
