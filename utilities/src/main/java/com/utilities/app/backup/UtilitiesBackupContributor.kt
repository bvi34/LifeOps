package com.utilities.app.backup

import android.content.Context
import com.operations.backupkit.AppId
import com.operations.backupkit.BackupContributor
import com.operations.backupkit.BackupSink
import com.operations.backupkit.BackupSource
import com.utilities.app.data.LexiconStore
import com.utilities.app.data.LookStore
import com.utilities.app.messages.seal.SealStore
import java.io.File

/**
 * Utilities' hook into the Operations Sandbox backup — and the smallest slice in the suite, on
 * purpose.
 *
 * ## What travels
 *
 * Two things, and they are both settings:
 *
 *  - **the appearance** (`utilities_look`): the keyboard's look and the thread's, which is the part
 *    somebody spent twenty minutes on and would be genuinely annoyed to redo;
 *  - **this app's own directory** under `filesDir`: the learned word list, and any font file the
 *    household pointed at. The font matters more than it looks: a face chosen from a document picker
 *    is a grant that does not survive a reinstall, which is exactly why the bytes were copied in
 *    (see `FontFiles`) — and copying them in is only useful if the copy travels.
 *
 * ## What does not, and why that is not an omission
 *
 * **The messages.** Every text on this phone is in Android's own `Telephony` provider, put there by
 * whatever app has been delivering them, and Utilities has never owned a single one — it draws a
 * window onto a store it does not keep. Carrying them here would mean inventing a second copy of
 * the household's message history, in an archive, so that a restore could write it back into a
 * provider that the platform's own backup already covers. Two copies of a conversation that can
 * drift apart is a worse answer than none.
 *
 * That is the whole shape of this app in one decision: it takes over surfaces, not data. Which is
 * also what makes every takeover reversible, and what makes this slice a few kilobytes.
 *
 * **The outbox.** An echo is a claim that the platform's store is missing a message this app sent
 * (see `OutboxStore`). Restored onto a new phone, every one of those claims is false. It is kept in
 * a preferences file deliberately named outside the prefix swept below, so the exclusion is a
 * property of the name rather than a filter somebody could relax.
 *
 * **The sealed-messaging sessions**, and this is the one exclusion that is not merely tidy — putting
 * them back would be *unsafe*. A Double Ratchet is a counter that only ever goes forward; restore
 * yesterday's copy and the phone re-derives message keys it has already used, which is the one
 * failure mode AES-GCM has no defence against. So `filesDir/utilities/seal` is skipped explicitly
 * rather than by a naming rule, because a directory inside the swept one needs a filter that a
 * reader will trip over. A restored phone re-establishes each conversation on its next message,
 * which costs one round trip and is the correct price.
 *
 * The **identity key** behind those sessions is a different matter and does survive — through the
 * vault rather than through the archive, on the same terms as Finance's bank tokens. See
 * `SealStore`: an identity that changed on every restore would make every contact see *the keys
 * changed*, which is the one alarm in this app that has to mean something.
 */
class UtilitiesBackupContributor(private val context: Context) : BackupContributor {

    override val appId = AppId.UTILITIES
    override val displayName = "Utilities"

    /**
     * The shape of what is carried, not a schema version — this app has no database.
     *
     * 1 is the appearance document plus the files directory. It goes up when an old archive's
     * contents would need interpreting rather than copying.
     */
    override val dataVersion = 1

    override fun backup(sink: BackupSink) {
        prefFiles().forEach { file ->
            sink.entry("$PREFS_PREFIX${file.name}").use { out -> file.inputStream().use { it.copyTo(out) } }
        }

        ownedFiles().forEach { file ->
            val rel = file.relativeTo(context.filesDir).invariantSeparatorsPath
            sink.entry(rel).use { out -> file.inputStream().use { it.copyTo(out) } }
        }
    }

    override fun restore(source: BackupSource) {
        val prefsDir = sharedPrefsDir().apply { mkdirs() }
        source.list().filter { it.startsWith(PREFS_PREFIX) }.forEach { rel ->
            val name = rel.removePrefix(PREFS_PREFIX)
            // Only ever this app's own preferences, which by the naming rule can never include the
            // outbox. See the class note.
            if (name.startsWith(PREFS_NAME_PREFIX) && name.isSafeName()) {
                source.open(rel)?.use { input -> File(prefsDir, name).outputStream().use { input.copyTo(it) } }
            }
        }

        source.list().filter { it.startsWith(FILES_PREFIX) }.forEach { rel ->
            // An archive entry is not allowed to name a path outside this app's directory. The
            // archive is the suite's own and normally trustworthy, and a restore writes files
            // wherever it is told to — a check that costs nothing belongs on that one code path.
            if (!rel.isSafePath()) return@forEach
            val target = File(context.filesDir, rel)
            target.parentFile?.mkdirs()
            source.open(rel)?.use { input -> target.outputStream().use { input.copyTo(it) } }
        }

        // Both stores are singletons that may already be open — the keyboard could be on screen
        // over another app while this runs. Re-reading here is what makes a restored look and a
        // restored word list take effect without the household being told to reboot the phone.
        LookStore.get(context).reload()
        LexiconStore.peek()?.reload()
    }

    private fun sharedPrefsDir() = File(context.applicationInfo.dataDir, "shared_prefs")

    private fun prefFiles(): List<File> =
        sharedPrefsDir()
            .listFiles { f -> f.isFile && f.name.startsWith(PREFS_NAME_PREFIX) && f.name.endsWith(".xml") }
            ?.toList().orEmpty()

    /** Everything under `filesDir/utilities`, at whatever depth — the word list and the fonts. */
    private fun ownedFiles(): List<File> =
        File(context.filesDir, LexiconStore.DIR_NAME)
            .walkTopDown()
            .filter { it.isFile }
            // A half-written word list is not worth carrying; the real one is beside it.
            .filterNot { it.name.endsWith(".tmp") }
            // And the ratchet state, which must NEVER be restored. See the class note.
            .filterNot { it.toPath().any { part -> part.toString() == SealStore.SESSION_DIR } }
            .toList()

    private fun String.isSafeName(): Boolean = !contains('/') && !contains('\\') && !contains("..")

    private fun String.isSafePath(): Boolean =
        startsWith(FILES_PREFIX) && !contains("..") && !contains('\\')

    companion object {
        private const val PREFS_PREFIX = "shared_prefs/"

        /**
         * The prefix that decides what travels.
         *
         * `outbox_utilities` does not start with it, which is the whole mechanism — see the class
         * note. Anything added to this app that should not survive a restore must be named so that
         * it also fails this test.
         */
        private const val PREFS_NAME_PREFIX = "utilities"

        /** Where this app's own files sit in the archive, mirroring their directory on disk. */
        private const val FILES_PREFIX = "${LexiconStore.DIR_NAME}/"
    }
}
