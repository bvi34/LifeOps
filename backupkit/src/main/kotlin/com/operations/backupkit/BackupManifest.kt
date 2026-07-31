package com.operations.backupkit

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * The table of contents written into every archive as `manifest.json`. It records what was backed
 * up (which apps, which entries) and enough provenance to make a later restore honest: when it was
 * taken, by which sandbox build, and each app's own data version so a restore can warn about a
 * format it predates.
 *
 * The manifest is authoritative for *which apps are present* — restore iterates it, not the raw zip
 * entries — so a truncated or partially-written archive can't be silently half-restored.
 */
data class BackupManifest(
    /** Bumped only if the *archive layout itself* changes; individual apps track their own version. */
    val formatVersion: Int = FORMAT_VERSION,
    val createdAt: Long,
    val sandboxVersion: String,
    val apps: List<AppEntry>
) {
    fun app(appId: AppId): AppEntry? = apps.firstOrNull { it.appId == appId.key }

    companion object {
        const val FORMAT_VERSION = 1
    }
}

/**
 * One app's slice of the archive. [entries] are relative paths *within* the app's directory (the
 * engine stores them under `<appId>/…`), so a contributor and the reader agree on names without
 * either knowing the zip prefix.
 */
data class AppEntry(
    val appId: String,
    val displayName: String,
    /** The contributor's own data-format version, echoed for restore-time compatibility checks. */
    val dataVersion: Int,
    val entries: List<String>
)

/** The manifest wire codec. Pretty-printed so an archive's `manifest.json` is human-inspectable. */
object BackupCodec {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun toJson(manifest: BackupManifest): String = gson.toJson(manifest)

    /** Parse a manifest; returns null on malformed/empty JSON rather than throwing. */
    fun fromJson(json: String): BackupManifest? = try {
        val parsed = gson.fromJson(json, BackupManifest::class.java)
        // Gson populates fields reflectively and can leave a non-null-typed field null when the key
        // is absent, so this guard is real at runtime even though the compiler sees it as constant.
        @Suppress("SENSELESS_COMPARISON")
        if (parsed == null || parsed.apps == null) null else parsed
    } catch (_: Exception) {
        null
    }
}
