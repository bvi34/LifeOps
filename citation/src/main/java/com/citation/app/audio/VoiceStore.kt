package com.citation.app.audio

import android.content.Context
import com.citation.core.speech.InstalledVoice
import com.citation.core.speech.VoiceLibrary
import com.citation.core.speech.VoiceModel
import com.citation.core.speech.VoiceOrigin
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
 *
 * The directory holds one file that is not a voice: the [UserVoiceRegistry] manifest saying what the
 * reader's own voices are called. It sits here rather than with the speech settings because it
 * describes this directory, and it is what makes a voice somebody added as listable as one the
 * catalogue names — see [installed].
 */
class VoiceStore(context: Context) {

    /** The voices directory, created on first use. */
    val dir: File = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    /** What the reader's own voices are called, since no build states it for them. */
    val added = UserVoiceRegistry(File(dir, MANIFEST))

    /** The weights file for [model], installed or not. */
    fun modelFile(model: VoiceModel): File = File(dir, model.modelFileName)

    /** The token table beside it, which the runtime reads to turn phonemes into model ids. */
    fun tokensFile(model: VoiceModel): File = File(dir, model.tokensFileName)

    /** Whether both of [model]'s files are present and non-empty. */
    fun isInstalled(model: VoiceModel): Boolean =
        modelFile(model).isNonEmpty() && tokensFile(model).isNonEmpty()

    /** Whether the voice named [voiceId] is installed, when only its id is to hand. */
    fun isInstalled(voiceId: String): Boolean =
        VoiceLibrary.modelFor(voiceId, added.voices())?.let { isInstalled(it) } ?: false

    /**
     * Every complete voice on disk: the catalogue's, then the reader's own.
     *
     * Only voices something knows by *name* are returned — the build for a catalogue voice, the
     * manifest for an added one. A stray `.onnx` in the directory belongs to neither, and has no
     * sample rate, no language and no name to show, so listing it would offer the reader a voice
     * nothing can actually load.
     */
    fun installed(): List<InstalledVoice> =
        VoiceLibrary.known(added.voices()).filter { isInstalled(it) }.map {
            InstalledVoice(it, modelFile(it).absolutePath, tokensFile(it).absolutePath)
        }

    /**
     * Write one of [model]'s two files from [source], verifying it before it counts as installed.
     *
     * Writes to `<name>.part` and renames on success, so a failure at any point leaves the previous
     * state intact and nothing half-written is ever visible to [installed]. [expectedSha256] is
     * checked when the catalogue states one; a mismatch deletes the download and reports it rather
     * than leaving a plausible-looking file that will crash a runtime later.
     */
    fun write(
        target: File,
        source: InputStream,
        expectedSha256: String? = null,
        onProgress: ((Long) -> Unit)? = null
    ): InstallResult {
        val partial = File(target.parentFile, target.name + PART_SUFFIX)
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
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
            if (expectedSha256 != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
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

    /**
     * Remove a voice: both its files, and — for one the reader added — the entry naming it.
     *
     * Both halves, because they are one thing. Deleting the files of an added voice but keeping its
     * definition would leave it in the picker as a voice permanently waiting to be downloaded, which
     * is not what anyone pressing a delete button meant. Deletes succeed on a full disk; installs do
     * not, which is why this is also the reader's way out of one.
     */
    fun delete(model: VoiceModel): Boolean {
        val weights = modelFile(model).delete()
        val tokens = tokensFile(model).delete()
        val forgotten = model.origin == VoiceOrigin.USER && added.forget(model.id)
        return weights || tokens || forgotten
    }

    /**
     * Install both of [model]'s files from streams the caller opens — a voice the reader already has.
     *
     * The pairing rule is [VoiceDownloader]'s, for the reason stated there: a voice is its pair, so
     * weights that failed take the token table with them rather than leaving something that looks
     * installed from one angle. Streams are opened lazily and closed here, because the caller's are
     * content URIs whose provider may be gone by the time the second one is wanted.
     *
     * Nothing is verified against a checksum, because there is no publisher to have stated one; the
     * file came off the reader's own device, which is a stronger claim than any hash it could carry.
     */
    fun importFrom(
        model: VoiceModel,
        openTokens: () -> InputStream?,
        openWeights: () -> InputStream?,
        onProgress: ((Long) -> Unit)? = null
    ): InstallResult {
        val tokens = runCatching { openTokens()?.use { write(tokensFile(model), it) } }
            .getOrElse { InstallResult.Failed(it.message ?: "That file could not be read") }
            ?: InstallResult.Failed("That file could not be read")
        if (tokens is InstallResult.Failed) return tokens

        val weights = runCatching {
            openWeights()?.use { write(modelFile(model), it, onProgress = onProgress) }
        }.getOrElse { InstallResult.Failed(it.message ?: "That file could not be read") }
            ?: InstallResult.Failed("That file could not be read")
        if (weights is InstallResult.Failed) tokensFile(model).delete()
        return weights
    }

    /** Clear any interrupted downloads — the reader's "why is this taking up space" answer. */
    fun clearPartials(): Int =
        dir.listFiles { file -> file.name.endsWith(PART_SUFFIX) }?.count { it.delete() } ?: 0

    /** Bytes the voices themselves occupy, for the storage screen. The manifest is not a voice. */
    fun totalBytes(): Long =
        dir.listFiles()?.filter { it.name != MANIFEST }?.sumOf { it.length() } ?: 0L

    private fun File.isNonEmpty(): Boolean = isFile && length() > 0

    /** How installing one file ended. */
    sealed class InstallResult {
        data class Installed(val bytes: Long) : InstallResult()
        data class Failed(val reason: String) : InstallResult()
    }

    companion object {
        private const val DIRECTORY = "voices"
        private const val PART_SUFFIX = ".part"

        /** Names the voices no build knows about. Not a voice, and never listed as one. */
        private const val MANIFEST = "voices.json"
    }
}
