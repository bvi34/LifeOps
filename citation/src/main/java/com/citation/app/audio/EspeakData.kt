package com.citation.app.audio

import android.content.Context
import java.io.File

/**
 * The pronunciation data the neural voice needs, unpacked out of the APK once.
 *
 * A Piper voice is a model of *sounds*, not of spelling: it is fed phonemes, and something has to
 * turn "Dr. Ames met Mrs. Hale in 1893" into them. That something is espeak-ng, inside the runtime,
 * and it reads its dictionaries from a directory on disk — so they have to exist as real files
 * before a single word can be spoken.
 *
 * They are **shipped in the APK** rather than downloaded, and trimmed to English rather than
 * complete. The full data set is 18 MB across 355 files; the parts an English voice actually reads —
 * the phoneme tables, the intonation data, the English dictionary and the English voice definitions
 * — are 848 KB across 13. That is small enough to carry always, which buys something worth more than
 * the megabytes: the first voice a reader downloads works offline the moment it lands, with no
 * second download to fail halfway and no half-unpacked state to reason about.
 *
 * The cost is stated rather than hidden: a voice for a language outside this set would need its own
 * dictionary and language files added here, and [VoiceCatalog] is English-only for that reason.
 */
object EspeakData {

    private const val ASSET_DIR = "espeak-ng-data"

    /**
     * The unpacked directory, unpacking it first if it is not already there.
     *
     * Returns `null` rather than throwing when it cannot be written — a full disk is a reason to
     * fall back to the platform voice, not a reason to crash a book.
     */
    fun directory(context: Context): File? {
        val target = File(context.filesDir, ASSET_DIR)
        val stamp = File(target, ".unpacked")
        if (stamp.isFile && stamp.readTextOrNull() == versionOf(context)) return target
        return runCatching {
            target.deleteRecursively()
            copyAsset(context, ASSET_DIR, target)
            stamp.writeText(versionOf(context))
            target
        }.getOrNull()
    }

    /**
     * The app's version code, written beside the unpacked files.
     *
     * An upgrade that ships new pronunciation data must not keep reading the old copy, and comparing
     * a stamp is cheaper and more reliable than comparing 13 files.
     */
    private fun versionOf(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        info.longVersionCode.toString()
    }.getOrDefault("0")

    private fun copyAsset(context: Context, path: String, target: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(path).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        children.forEach { child -> copyAsset(context, "$path/$child", File(target, child)) }
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()
}
