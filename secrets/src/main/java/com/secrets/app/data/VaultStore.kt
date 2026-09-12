package com.secrets.app.data

import android.content.Context
import android.util.Log
import com.operations.vaultkit.SecretSources
import com.operations.vaultkit.SecretsAccess
import com.operations.vaultkit.VaultCrypto
import com.operations.vaultkit.VaultDocument
import com.operations.vaultkit.VaultEnvelope
import com.operations.vaultkit.VaultFile
import com.operations.vaultkit.VaultJson
import com.operations.vaultkit.VaultMerge
import com.operations.vaultkit.VaultState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The vault, open or shut.
 *
 * Everything the app can see passes through here, and the whole design is one sentence: **the
 * plaintext exists in this object's memory and nowhere else.** There is no database, no cache file,
 * no "last opened item" holding a password, and nothing derived from the document is written
 * anywhere but back into the sealed file.
 *
 * ## Locked, unlocked, absent
 *
 * Three states, and the difference between them is what this object is holding:
 *
 *  - **absent** — no file on disk. Nothing to hold.
 *  - **locked** — a file, parsed as far as its header (so the app can say "there is a vault here"
 *    without a passphrase), and no key. [document] is null.
 *  - **unlocked** — the vault key and the decrypted document, both in memory, until [lock] or the
 *    auto-lock takes them away.
 *
 * ## Saving
 *
 * Every change re-seals the whole document and writes the whole file. That is deliberate and it is
 * cheap: a household's vault is kilobytes, AES is fast, and the alternative — a store that appends,
 * or one that keeps rows — leaves the old version of a password recoverable from whatever it
 * appended to. The one thing that is *not* redone on a save is the key derivation: the header and
 * the wrapped key are reused, so a save costs one AES pass rather than 310,000 rounds of PBKDF2
 * (see [VaultEnvelope.reseal]).
 *
 * ## Threading
 *
 * The mutating calls come from two places with different rules: the app's own screens (coroutines,
 * IO dispatcher) and the broker (whatever thread another app happened to be on when it saved a
 * token). So the state transitions are guarded by [lockObject] and the blocking forms are public —
 * a caller on the main thread pays a few milliseconds for a small file write, which is honest and
 * bounded, and much better than a "write" that returns before it has written.
 */
class VaultStore(context: Context) {

    private val files = VaultFileStore(context)
    private val deviceUnlock = DeviceUnlock(context)

    private val lockObject = Any()

    /** The parsed envelope: present whenever a file exists, whether or not it is open. */
    @Volatile
    private var envelope: VaultFile? = null

    /** The vault key. Non-null exactly while unlocked, and wiped on the way out. */
    @Volatile
    private var vaultKey: ByteArray? = null

    private val _state = MutableStateFlow(VaultState.ABSENT)
    val state: StateFlow<VaultState> = _state.asStateFlow()

    private val _document = MutableStateFlow<VaultDocument?>(null)

    /** The decrypted document, or null while locked. Every screen reads this and nothing else. */
    val document: StateFlow<VaultDocument?> = _document.asStateFlow()

    /** When the vault was last touched, for the auto-lock. Not persisted: a boot locks the vault. */
    @Volatile
    var lastInteractionAt: Long = 0L
        private set

    /** Set when a save fails, so a screen can say so rather than silently losing an edit. */
    private val _saveFailed = MutableStateFlow(false)
    val saveFailed: StateFlow<Boolean> = _saveFailed.asStateFlow()

    init {
        refreshState()
    }

    /** Re-read what is on disk. Called at start-up and after a restore has staged something. */
    fun refreshState() {
        synchronized(lockObject) {
            if (vaultKey != null) return
            val bytes = files.read()
            envelope = bytes?.let { VaultEnvelope.decode(it) }
            publishState(
                when {
                    bytes == null -> VaultState.ABSENT
                    // A file that will not parse is still a vault as far as the household is
                    // concerned — saying "no vault" would invite them to make a new one over the
                    // top of it.
                    else -> VaultState.LOCKED
                }
            )
        }
    }

    val exists: Boolean get() = files.exists()

    /** True when the file exists but could not be parsed — a corrupt or truncated vault. */
    val unreadable: Boolean get() = files.exists() && envelope == null

    val hasPreviousGeneration: Boolean get() = files.readPrevious() != null

    val stagedRestores: List<File> get() = files.stagedRestores()

    /** How the device unlock is configured, for the settings screen. */
    val device: DeviceUnlock get() = deviceUnlock

    // --- Opening and closing ---------------------------------------------------------------------

    enum class UnlockResult { UNLOCKED, WRONG_PASSPHRASE, NO_VAULT, CORRUPT }

    /**
     * Create a vault. [passphrase] is the only thing that will ever open it, and this app cannot
     * recover it — which is said on the screen that calls this, in those words.
     */
    suspend fun create(passphrase: CharArray): Boolean = withContext(Dispatchers.IO) {
        synchronized(lockObject) {
            if (files.exists()) return@withContext false
            val document = VaultDocument.EMPTY.copy(updatedAt = System.currentTimeMillis())
            val key = VaultCrypto.newVaultKey()
            val file = VaultEnvelope.create(passphrase, VaultJson.encode(document), key)
            if (!files.write(VaultEnvelope.encode(file))) {
                VaultCrypto.wipe(key)
                return@withContext false
            }
            envelope = file
            vaultKey = key
            _document.value = document
            publishState(VaultState.UNLOCKED)
            touch()
            true
        }
    }.also { if (it) openedUp() }

    /**
     * Open the vault with [passphrase].
     *
     * The expensive call in this app, by design: one KDF pass at the file's own iteration count. A
     * wrong passphrase costs exactly the same as a right one, which is the property that matters —
     * a fast "no" is a rate limit somebody can measure.
     */
    suspend fun unlock(passphrase: CharArray): UnlockResult = withContext(Dispatchers.IO) {
        val file = synchronized(lockObject) {
            refreshStateLocked()
            envelope
        } ?: return@withContext if (files.exists()) UnlockResult.CORRUPT else UnlockResult.NO_VAULT

        val key = VaultEnvelope.unwrapKey(file, passphrase) ?: return@withContext UnlockResult.WRONG_PASSPHRASE
        val document = VaultEnvelope.read(file, key)?.let { VaultJson.decode(it) }
        if (document == null) {
            VaultCrypto.wipe(key)
            return@withContext UnlockResult.CORRUPT
        }
        adopt(key, document)
        UnlockResult.UNLOCKED
    }.also { if (it == UnlockResult.UNLOCKED) openedUp() }

    /**
     * Open the vault with the key the device kept for the fingerprint shortcut.
     *
     * The caller must have satisfied the lock screen first — see [DeviceUnlock], which is where that
     * requirement is explained rather than merely stated.
     */
    suspend fun unlockWithDeviceKey(): UnlockResult = withContext(Dispatchers.IO) {
        val file = synchronized(lockObject) {
            refreshStateLocked()
            envelope
        } ?: return@withContext UnlockResult.NO_VAULT

        val key = deviceUnlock.vaultKey() ?: return@withContext UnlockResult.WRONG_PASSPHRASE
        val document = VaultEnvelope.read(file, key)?.let { VaultJson.decode(it) }
        if (document == null) {
            VaultCrypto.wipe(key)
            // A stored key that no longer opens the vault means the passphrase was changed on
            // another install or the file was replaced; the shortcut is stale and is thrown away
            // rather than left to fail every morning.
            deviceUnlock.disable()
            return@withContext UnlockResult.WRONG_PASSPHRASE
        }
        adopt(key, document)
        UnlockResult.UNLOCKED
    }.also { if (it == UnlockResult.UNLOCKED) openedUp() }

    /**
     * What happens the moment the vault is open, whichever way it was opened.
     *
     * Both directions of the seam, in the order that cannot lose anything:
     *
     *  1. **Flush.** Credentials written while the vault was shut are sitting in memory; they are
     *     the newest copies of themselves and they go in first, so step 2 cannot read a stale value
     *     out of the file and hand it back to the app that had already replaced it.
     *  2. **Rehydrate.** Every app is asked to take back what it is missing. On an ordinary unlock
     *     that is nothing at all and costs a map lookup per app; on the first unlock after a restore
     *     it is every credential the household has, put back without them opening a single app.
     *
     * Step 2 is the whole point of the vault travelling in the archive. Before it, a restored phone
     * got its credentials back one at a time and only when something happened to ask for one while
     * the vault was open — so a sync that ran at six in the morning against a shut vault reported a
     * bank connection that looked as though it had never been set up.
     *
     * On IO because an app's rehydrate reads its database to find out which connections it has, and
     * unlock is called from a screen.
     */
    private suspend fun openedUp() = withContext(Dispatchers.IO) {
        SecretsAccess.flushPending()
        SecretSources.rehydrateAll()
    }

    /** Shut the vault: wipe the key, drop the document, tell everyone. */
    fun lock() {
        synchronized(lockObject) {
            VaultCrypto.wipe(vaultKey)
            vaultKey = null
            _document.value = null
            publishState(if (files.exists()) VaultState.LOCKED else VaultState.ABSENT)
        }
    }

    /** Push the auto-lock back. Called on every interaction the user makes. */
    fun touch() {
        lastInteractionAt = System.currentTimeMillis()
    }

    /** Should the vault have locked itself by [now], given [timeoutMillis]? */
    fun shouldAutoLock(now: Long, timeoutMillis: Long): Boolean =
        _state.value == VaultState.UNLOCKED &&
            timeoutMillis > 0 &&
            now - lastInteractionAt >= timeoutMillis

    // --- Changing what is in it ------------------------------------------------------------------

    /**
     * Apply [mutate] to the document and save. Returns false if the vault is shut or the save failed.
     *
     * Blocking, and called from both the app's coroutines and the broker. The mutation runs *inside*
     * the lock so two writers cannot each read the same document, change different halves of it and
     * save in turn — which with a whole-document format is how an edit disappears.
     */
    fun mutateBlocking(mutate: (VaultDocument) -> VaultDocument): Boolean {
        synchronized(lockObject) {
            val key = vaultKey ?: return false
            val file = envelope ?: return false
            val current = _document.value ?: return false

            val updated = mutate(current).copy(updatedAt = System.currentTimeMillis())
            if (updated == current) return true

            if (updated.version > VaultDocument.DOCUMENT_VERSION) {
                // A vault written by a newer build of the app. Reading it is fine; writing it back
                // through this build's codec would drop whatever the newer one added, so it is
                // refused rather than silently narrowed.
                Log.w(TAG, "Refusing to write a document from a newer version of this app")
                _saveFailed.value = true
                return false
            }

            val resealed = VaultEnvelope.reseal(file, key, VaultJson.encode(updated))
            if (!files.write(VaultEnvelope.encode(resealed))) {
                _saveFailed.value = true
                return false
            }
            envelope = resealed
            _document.value = updated
            _saveFailed.value = false
            touch()
            return true
        }
    }

    suspend fun mutate(mutate: (VaultDocument) -> VaultDocument): Boolean =
        withContext(Dispatchers.IO) { mutateBlocking(mutate) }

    /**
     * Change the master passphrase.
     *
     * The vault key does not change, so nothing in the document is re-encrypted and the device
     * shortcut keeps working — it holds the key, not the passphrase. What does change is the salt
     * and the wrapping, so the old passphrase stops opening the file the moment this returns true.
     */
    suspend fun changePassphrase(current: CharArray, replacement: CharArray): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(lockObject) {
                val file = envelope ?: return@withContext false
                val document = _document.value ?: return@withContext false
                // Verified against the file rather than against the key in memory: somebody changing
                // a passphrase must prove they know the current one, even on an already-open vault.
                val key = VaultEnvelope.unwrapKey(file, current) ?: return@withContext false
                val rewrapped = VaultEnvelope.rewrap(key, VaultJson.encode(document), replacement)
                if (!files.write(VaultEnvelope.encode(rewrapped))) {
                    VaultCrypto.wipe(key)
                    return@withContext false
                }
                envelope = rewrapped
                VaultCrypto.wipe(vaultKey)
                vaultKey = key
                touch()
                true
            }
        }

    /**
     * Merge a vault that came out of a backup archive.
     *
     * Needs *that* vault's passphrase, which may not be this one: the archive could be a year old
     * and predate a passphrase change. The merge itself is [VaultMerge]; what happens here is the
     * decrypting of the incoming file and the saving of the result.
     */
    suspend fun mergeStaged(staged: File, passphrase: CharArray): VaultMerge.Outcome? =
        withContext(Dispatchers.IO) {
            val incoming = runCatching { staged.readBytes() }.getOrNull() ?: return@withContext null
            val file = VaultEnvelope.decode(incoming) ?: return@withContext null
            val key = VaultEnvelope.unwrapKey(file, passphrase) ?: return@withContext null
            val document = VaultEnvelope.read(file, key)?.let { VaultJson.decode(it) }
            VaultCrypto.wipe(key)
            if (document == null) return@withContext null

            var outcome: VaultMerge.Outcome? = null
            val saved = mutateBlocking { mine ->
                VaultMerge.merge(mine, document, System.currentTimeMillis())
                    .also { outcome = it }
                    .document
            }
            if (saved) outcome else null
        }

    /** Throw away a staged archive vault without merging it. */
    fun discardStaged(file: File): Boolean = files.discardStaged(file)

    /**
     * Try the previous generation of the file, for a vault that will not parse.
     *
     * Not offered casually — the settings screen shows it only when [unreadable] is true — because
     * "go back one save" is a data-loss button wearing a helpful hat.
     */
    suspend fun rollBackToPrevious(): Boolean = withContext(Dispatchers.IO) {
        synchronized(lockObject) {
            val previous = files.readPrevious() ?: return@withContext false
            if (VaultEnvelope.decode(previous) == null) return@withContext false
            if (!files.write(previous)) return@withContext false
            lock()
            refreshStateLocked()
            true
        }
    }

    /**
     * Delete the vault and everything that unlocks it. The end of the line for a forgotten
     * passphrase, and the screen that offers it says exactly that.
     */
    suspend fun destroy(): Boolean = withContext(Dispatchers.IO) {
        synchronized(lockObject) {
            deviceUnlock.disable()
            files.deleteAll()
            VaultCrypto.wipe(vaultKey)
            vaultKey = null
            envelope = null
            _document.value = null
            publishState(VaultState.ABSENT)
            true
        }
    }

    /**
     * The way back from a forgotten passphrase: throw the vault away, make a new one, and ask every
     * app to file what it still holds.
     *
     * ## Why this is not a recovery
     *
     * Nothing here decrypts the old vault. It cannot — that is the property the whole app rests on,
     * and a reset that could read what the old passphrase protected would mean the passphrase never
     * protected it. So this is a **deletion followed by a rebuild**, and what comes back is only what
     * somebody else still has:
     *
     *  - **Managed credentials come back**, because they were never the vault's only copy. Finance's
     *    tokens, Citation's sign-ins and the rest are sitting in each app's own encrypted store on
     *    this phone, and [SecretSources] asks each app to file them again. That is the whole point of
     *    this call: a forgotten passphrase should not cost the household nine reconnections when the
     *    credentials are, at this moment, twelve inches away.
     *  - **Everything typed into Secrets is gone.** The logins, the notes, the cards. The vault was
     *    the only place they existed. The screen that offers this says so before it runs.
     *
     * On a phone where the apps are also empty — a fresh install, a restore in which the vault was
     * the thing that failed — the refill returns nothing, and reporting that honestly is better than
     * reporting a success of zero.
     *
     * ## What else it takes with it
     *
     * The device shortcut ([DeviceUnlock]), because it held the *old* vault key. Backups taken before
     * the reset are untouched and still open with the old passphrase — so if that passphrase is
     * remembered later, the archive is still a way back to what was typed in, and Settings will merge
     * it. That is worth knowing before pressing this, and it is on the screen too.
     */
    suspend fun resetForgottenPassphrase(replacement: CharArray): SecretSources.Refill? {
        if (!destroy()) return null
        if (!create(replacement)) return null
        // Only after the new vault is open, so the writes land rather than queue — the queue would
        // work, but a reset that reported "3 filed" while holding them in memory would be a reset
        // that loses them to a crash on the way back to the list.
        return SecretSources.refileAll()
    }

    /**
     * Turn the device shortcut on, storing the *current* vault key behind the Keystore.
     *
     * Only possible while unlocked, because the key it stores is the one this object is holding.
     */
    fun enableDeviceUnlock(): Boolean {
        val key = vaultKey ?: return false
        return deviceUnlock.enable(key)
    }

    private fun adopt(key: ByteArray, document: VaultDocument) {
        synchronized(lockObject) {
            VaultCrypto.wipe(vaultKey)
            vaultKey = key
            _document.value = document
            publishState(VaultState.UNLOCKED)
            _saveFailed.value = false
            touch()
        }
    }

    /**
     * Move the vault to [next] and tell the rest of the suite.
     *
     * Every state transition goes through here, and the announcement is the reason it exists. The
     * [StateFlow] is what *this app's* screens read; it is invisible to the other ten, and the one
     * fact they most need is the one it was hiding. A credential Finance wrote while the vault was
     * shut sits in [SecretsAccess]'s queue until somebody unlocks — and nothing anywhere was in a
     * position to mention that there was a reason to.
     *
     * The announcement happens inside [lockObject], which is safe because a watcher is forbidden
     * from blocking (see [SecretsAccess.VaultWatcher]) and because the two values it reads —
     * the broker's state and the queue depth — are a `StateFlow` read and a small `synchronized`
     * block, neither of which comes back through this monitor.
     */
    private fun publishState(next: VaultState) {
        val changed = _state.value != next
        _state.value = next
        if (changed) SecretsAccess.announceChanged()
    }

    /** [refreshState]'s body, for callers already holding [lockObject]. */
    private fun refreshStateLocked() {
        if (vaultKey != null) return
        val bytes = files.read()
        envelope = bytes?.let { VaultEnvelope.decode(it) }
        publishState(if (bytes == null) VaultState.ABSENT else VaultState.LOCKED)
    }

    private companion object {
        const val TAG = "VaultStore"
    }
}
