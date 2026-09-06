package com.citation.app.audio

import android.content.Context
import com.citation.core.speech.InstalledVoice
import com.citation.core.speech.VoiceCatalog
import com.citation.core.speech.VoiceModel
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Where downloaded voices live, and what counts as one being installed.
 *
 * The directory is deliberately *neither* of Citation's two content stores. It is not disposable
 * cache: the eviction job walks that directory, and a reader who spent sixty megabytes and a coffee
 * shop's wifi on a voice must not lose it to a routine sweep — least of all on the flight where
 * they meant to use it. Nor is it sovereign content: nothing here is the reader's, it is a
 * redistributable file that can be fetched again, and the integrity manifest and backup have no
 * business carrying it. So voices get a store of their own, sized and cleared explicitly by the
 * reader in the same place they manage the rest of their storage.
 *
 * A voice is installed when *both* its files are present and non-empty. That rule exists because
 * the failure that actually happens is a download interrupted at forty megabytes, and a runtime
 * handed half a model does not fail politely. Downloads land on a `.part` file and are renamed only
 * after they verify, so an interrupted one is never mistaken for a voice.
 */
class VoiceStore(context: Context) {

    /** The voices directory, created on first use. */
    val dir: File = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    /** The weights file for [model], installed or not. */
    fun modelFile(model: VoiceModel): File = File(dir, model.modelFileName)

    /** The config beside it, which the runtime reads for sample rate and phonemes. */
    fun configFile(model: VoiceModel): File = File(dir, model.configFileName)

    /** Whether both of [model]'s files are present and non-empty. */
    fun isInstalled(model: VoiceModel): Boolean =
        modelFile(model).isNonEmpty() && configFile(model).isNonEmpty()

    /** Whether the voice named [voiceId] is installed, when only its id is to hand. */
    fun isInstalled(voiceId: String): Boolean =
        VoiceCatalog.modelFor(voiceId)?.let { isInstalled(it) } ?: false

    /**
     * Every complete voice on disk, catalogue order.
     *
     * Only voices this build knows by name are returned: a stray `.onnx` in the directory has no
     * sample rate, no language and no name to show, so listing it would offer the reader a voice
     * nothing can actually load.
     */
    fun installed(): List<InstalledVoice> =
        VoiceCatalog.VOICES.filter { isInstalled(it) }.map {
            InstalledVoice(it, modelFile(it).absolutePath, configFile(it).absolutePath)
        }

    /**
     * Write one of [model]'s two files from [source], verifying it before it counts as installed.
     *
     * Writes to `<name>.part` and renames on success, so a failure at any point leaves the previous
     * state intact and nothing half-written is ever visible to [installed]. [expectedMd5] is checked
     * when the catalogue states one; a mismatch deletes the download and reports it rather than
     * leaving a plausible-looking file that will crash a runtime later.
     */
    fun write(
        target: File,
        source: InputStream,
        expectedMd5: String? = null,
        onProgress: ((Long) -> Unit)? = null
    ): InstallResult {
        val partial = File(target.parentFile, target.name + PART_SUFFIX)
        return try {
            val digest = MessageDigest.getInstance("MD5")
            var written = 0L
            partial.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    written += read
                    onProgress?.invoke(written)
                }
            }
            if (written == 0L) {
                partial.delete()
                return InstallResult.Failed("Downloaded nothing")
            }
            if (expectedMd5 != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expectedMd5, ignoreCase = true)) {
                    partial.delete()
                    return InstallResult.Failed("The download did not verify; it may have been cut short")
                }
            }
            target.delete()
            if (!partial.renameTo(target)) {
                partial.delete()
                return InstallResult.Failed("Could not store the voice")
            }
            InstallResult.Installed(written)
        } catch (e: Exception) {
            partial.delete()
            InstallResult.Failed(e.message ?: "Could not store the voice")
        }
    }

    /** Remove a voice and both its files. Deletes succeed on a full disk; installs do not. */
    fun delete(model: VoiceModel): Boolean {
        val weights = modelFile(model).delete()
        val config = configFile(model).delete()
        return weights || config
    }

    /** Clear any interrupted downloads — the reader's "why is this taking up space" answer. */
    fun clearPartials(): Int =
        dir.listFiles { file -> file.name.endsWith(PART_SUFFIX) }?.count { it.delete() } ?: 0

    /** Bytes the voices directory occupies, for the storage screen. */
    fun totalBytes(): Long =
        dir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun File.isNonEmpty(): Boolean = isFile && length() > 0

    /** How installing one file ended. */
    sealed class InstallResult {
        data class Installed(val bytes: Long) : InstallResult()
        data class Failed(val reason: String) : InstallResult()
    }

    companion object {
        private const val DIRECTORY = "voices"
        private const val PART_SUFFIX = ".part"
    }
}
