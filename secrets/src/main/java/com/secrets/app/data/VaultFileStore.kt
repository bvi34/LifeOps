package com.secrets.app.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Where the sealed vault lives on disk, and how it gets there without ever being half-written.
 *
 * ## The write
 *
 * Write to `vault.opsv.tmp`, flush it, `fsync` it, then rename over `vault.opsv`. Rename within a
 * directory is atomic, so at every instant the real file is either entirely the old vault or
 * entirely the new one — never the first three kilobytes of the new one, which for an
 * authenticated-encryption format is a vault that cannot be opened at all.
 *
 * The `fsync` before the rename is the part people leave out. Without it the rename can reach the
 * filesystem before the bytes do, and a phone that loses power in that window comes back with a
 * complete-looking file full of nothing. It costs a few milliseconds on a save that happens when
 * somebody edits a password.
 *
 * ## The previous copy
 *
 * The file being replaced is kept as `vault.prev` — one generation, no more. It is not a version
 * history and is not offered as one; it is there for the single case where the current file is
 * unreadable and the household would otherwise have nothing. Both files are the same encrypted
 * format under the same passphrase, so keeping it costs no secrecy: an attacker who can read one
 * can read the other, and neither without the passphrase.
 *
 * ## Where it is not
 *
 * `filesDir`, not the cache, not external storage, and not `getSharedPreferences`. The vault is the
 * one thing in this app that must survive an aggressive cache clear and must never be world- or
 * app-group-readable.
 */
class VaultFileStore(context: Context) {

    private val dir = File(context.applicationInfo.dataDir, "files/$DIR_NAME")

    val vaultFile: File get() = File(dir, VAULT_NAME)

    private val tempFile: File get() = File(dir, "$VAULT_NAME.tmp")

    val previousFile: File get() = File(dir, PREVIOUS_NAME)

    /** Is there a vault on this phone at all? The question the sandbox tile and every app asks. */
    fun exists(): Boolean = vaultFile.exists() && vaultFile.length() > 0

    fun read(): ByteArray? = readFile(vaultFile)

    /** The generation before the current one, for the recovery path. */
    fun readPrevious(): ByteArray? = readFile(previousFile)

    private fun readFile(file: File): ByteArray? = try {
        if (file.exists() && file.length() > 0) file.readBytes() else null
    } catch (e: Exception) {
        Log.w(TAG, "Could not read ${file.name}", e)
        null
    }

    /** Write [bytes] as the vault. Returns false rather than throwing; the caller shows that. */
    fun write(bytes: ByteArray): Boolean = try {
        dir.mkdirs()
        tempFile.outputStream().use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (vaultFile.exists()) {
            previousFile.delete()
            // A copy rather than a rename: a rename would leave no vault at all in the instant
            // between the two operations, and that instant is exactly when phones die.
            vaultFile.copyTo(previousFile, overwrite = true)
        }
        val renamed = tempFile.renameTo(vaultFile)
        if (!renamed) Log.w(TAG, "Could not replace the vault file")
        renamed
    } catch (e: Exception) {
        Log.w(TAG, "Could not write the vault", e)
        tempFile.delete()
        false
    }

    /**
     * Stage a vault that arrived in a backup archive, under a name a restore can find later.
     *
     * A restore does **not** overwrite a live vault (see `SecretsBackupContributor`); it leaves the
     * archived one here, and the household merges it from Settings once they can unlock both. This
     * is the only place in the suite where a restore deliberately does not restore.
     */
    fun stageRestored(bytes: ByteArray): File? = try {
        dir.mkdirs()
        val staged = File(dir, "$RESTORED_PREFIX${System.currentTimeMillis()}$RESTORED_SUFFIX")
        staged.writeBytes(bytes)
        staged
    } catch (e: Exception) {
        Log.w(TAG, "Could not stage the restored vault", e)
        null
    }

    /** Every staged vault waiting to be merged, newest first. */
    fun stagedRestores(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith(RESTORED_PREFIX) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun discardStaged(file: File): Boolean = file.delete()

    /**
     * Delete everything. The "start again" on the settings screen, and the only way back from a
     * forgotten passphrase — which is not recoverable, by construction, and is said in those words
     * on the screen that offers this.
     */
    fun deleteAll() {
        vaultFile.delete()
        previousFile.delete()
        tempFile.delete()
        stagedRestores().forEach { it.delete() }
    }

    companion object {
        private const val TAG = "VaultFileStore"

        private const val DIR_NAME = "vault"

        /**
         * The name the backup contributor writes and reads, and the extension `file` will not
         * recognise — the format announces itself in its first eight bytes instead
         * (`com.operations.vaultkit.VaultEnvelope.looksLikeVault`).
         */
        const val VAULT_NAME = "vault.opsv"
        const val PREVIOUS_NAME = "vault.prev"

        const val RESTORED_PREFIX = "restored-"
        const val RESTORED_SUFFIX = ".opsv"
    }
}
