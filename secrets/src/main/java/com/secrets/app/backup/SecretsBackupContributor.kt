package com.secrets.app.backup

import android.content.Context
import com.operations.backupkit.AppId
import com.operations.backupkit.BackupContributor
import com.operations.backupkit.BackupSink
import com.operations.backupkit.BackupSource
import com.operations.vaultkit.VaultEnvelope
import com.secrets.app.SecretsApp
import com.secrets.app.data.VaultFileStore
import java.io.File

/**
 * Secrets' hook into the Operations Sandbox backup — and the one contributor in the suite that
 * deliberately writes credentials into the archive.
 *
 * ## Why this is the opposite decision to every other app's, and why both are right
 *
 * `FinanceBackupContributor` leaves every token behind, and says why at length: a zip in a cloud
 * drive carrying a Plaid access token is a standing read grant on a bank account that cannot be
 * rotated by changing a password and whose escape nobody would notice. `CitationBackupContributor`
 * does the same with catalogue sign-ins. They are right, and the reason they are right is not "a
 * backup must not contain credentials" — it is that *those* stores keep credentials in a form the
 * archive would make readable.
 *
 * What travels here is not a credential. It is a **sealed file whose key is not in the archive, not
 * on the phone, and not anywhere a machine can reach it**: it is a passphrase in somebody's head,
 * stretched through 310,000 rounds of PBKDF2 into a key that wraps the one the vault is encrypted
 * with. Copy this file out of the zip and what you have is the same thing an attacker who stole the
 * phone would have, and no more.
 *
 * That is the whole argument for the app. The other apps' credentials now survive a restore, and
 * the archive is no more dangerous than the passphrase is weak.
 *
 * ## What is left behind
 *
 * The **device unlock** (`secure_secrets_device`), which holds the vault key wrapped by *this*
 * phone's Keystore. It is excluded the same way Finance excludes its token store — by a name that
 * fails the prefix test rather than by a filter somebody could relax — and for a sharper reason: a
 * backup containing both the sealed vault and a device-unwrappable copy of its key would be a
 * backup containing the vault in plaintext, on any phone that could rebuild that key. The exclusion
 * is what keeps the paragraph above true.
 *
 * Also left behind: the `.prev` generation and any staged restores. One vault travels, the current
 * one.
 *
 * ## Restore does not overwrite
 *
 * This is the only contributor that refuses to put back what it was given. Every other app's restore
 * is a whole-file swap because the worst case is a re-fetch; here the worst case is every password
 * added since the backup, gone, with nowhere to fetch them from. So the archived vault is **staged**
 * — written beside the live one under `restored-<time>.opsv` — and Secrets offers it on the settings
 * screen as something to merge, which needs that vault's passphrase and produces a report of what
 * changed (see [com.operations.vaultkit.VaultMerge]).
 *
 * The one case where it *is* applied outright is the case it was built for: **there is no vault on
 * this phone.** A new install, a new phone, a restore of everything at once. Then there is nothing
 * to lose and the archived vault becomes the vault, which is precisely what makes the credentials
 * come back.
 */
class SecretsBackupContributor(private val context: Context) : BackupContributor {

    override val appId = AppId.SECRETS
    override val displayName = "Secrets"

    override val dataVersion = VaultEnvelope.FORMAT_VERSION

    private val files = VaultFileStore(context)

    override fun backup(sink: BackupSink) {
        val vault = files.vaultFile
        if (vault.exists() && vault.length() > 0) {
            sink.entry(VAULT_ENTRY).use { out -> vault.inputStream().use { it.copyTo(out) } }
        }

        // The settings — auto-lock, clipboard timeout — which are preferences and not secrets. The
        // device key store is not among them: its name does not begin with `secrets`, which is the
        // whole mechanism (see DeviceUnlock).
        prefFiles().forEach { file ->
            sink.entry("$PREFS_PREFIX${file.name}").use { out -> file.inputStream().use { it.copyTo(out) } }
        }
    }

    override fun restore(source: BackupSource) {
        val prefsDir = sharedPrefsDir().apply { mkdirs() }
        source.list().filter { it.startsWith(PREFS_PREFIX) }.forEach { rel ->
            val name = rel.removePrefix(PREFS_PREFIX)
            // Defensive, and doubly so here: only ever write this app's own pref files, which by the
            // naming rule can never include the device key store.
            if (name.startsWith(PREFS_NAME_PREFIX)) {
                source.open(rel)?.use { input -> File(prefsDir, name).outputStream().use { input.copyTo(it) } }
            }
        }

        val incoming = source.open(VAULT_ENTRY)?.use { it.readBytes() } ?: return
        if (!VaultEnvelope.looksLikeVault(incoming)) return

        if (files.exists()) {
            // There is already a vault here. Staging rather than overwriting — see the class note.
            files.stageRestored(incoming)
        } else {
            files.write(incoming)
        }

        // Whichever branch ran, the store's idea of what is on disk is now stale: an app that was
        // opened before the restore is holding a parsed header for a file that has changed, or none
        // for a file that now exists. Re-reading here is what makes the vault appear without a
        // restart, on the one screen where "restart the app" is the least welcome instruction.
        SecretsApp.peek()?.vault?.refreshState()
    }

    private fun sharedPrefsDir() = File(context.applicationInfo.dataDir, "shared_prefs")

    private fun prefFiles(): List<File> =
        sharedPrefsDir()
            .listFiles { f -> f.isFile && f.name.startsWith(PREFS_NAME_PREFIX) && f.name.endsWith(".xml") }
            ?.toList().orEmpty()

    companion object {
        /** The vault, byte for byte, as it sits on disk. */
        const val VAULT_ENTRY = "vault.opsv"

        private const val PREFS_PREFIX = "shared_prefs/"

        /**
         * The prefix that decides what travels.
         *
         * `secure_secrets_device` does not start with it. Anything added to this app that could
         * unwrap the vault must be named so that it also fails this test.
         */
        private const val PREFS_NAME_PREFIX = "secrets"
    }
}
