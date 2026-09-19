package com.secrets.app.ui.importer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.credentials.providerevents.ProviderEventsManager
import androidx.credentials.providerevents.exception.ImportCredentialsCancellationException
import androidx.credentials.providerevents.exception.ImportCredentialsException
import androidx.credentials.providerevents.exception.ImportCredentialsInvalidJsonException
import androidx.credentials.providerevents.exception.ImportCredentialsNoExportOptionException
import androidx.credentials.providerevents.exception.ImportCredentialsProviderConfigurationException
import androidx.credentials.providerevents.transfer.CredentialTypes
import androidx.credentials.providerevents.transfer.ImportCredentialsRequest
import androidx.credentials.providerevents.transfer.KnownExtensions
import com.operations.vaultkit.CredentialExchange
import com.operations.vaultkit.ImportFile
import com.operations.vaultkit.VaultImport
import com.secrets.app.data.VaultStore
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The four states of bringing somebody's passwords in from somewhere else.
 *
 * Idle, reading, reviewing, done — and the reviewing one is the reason this is a screen rather than
 * a button. An import is the only operation in this app that changes hundreds of items at once, and
 * the household is the only party that can say whether the export they just picked is *newer* than
 * the vault. So nothing is written until somebody has seen the list, and the entries that would
 * overwrite a password already here start unticked (see [VaultImport.Plan]).
 *
 * ## Where the plaintext is, while this screen is open
 *
 * In this object, in [State.Reviewing], and nowhere else — no file, no cache, no clipboard. It is
 * the same rule [VaultStore] follows for the document itself, and it holds for the same reason it
 * has to: this state is every password the household has, in the clear. Locking the vault pops this
 * screen off the back stack (the effect in `MainActivity`), which clears the view model with it.
 *
 * The one copy this app cannot do anything about is the export file itself, sitting in Downloads
 * where the browser put it. The screen says so, twice, because deleting it is the last step of the
 * import and the one nobody remembers.
 */
class ImportViewModel(private val store: VaultStore) : ViewModel() {

    sealed interface State {

        /** Nothing picked yet. */
        data object Idle : State

        data object Reading : State

        /** [reason] is a sentence about what to do next, not an error code. */
        data class Failed(val reason: String) : State

        data class Reviewing(
            val plan: VaultImport.Plan,
            val selected: Set<String>
        ) : State {
            val ready: Boolean get() = selected.isNotEmpty()
        }

        data class Done(
            /** What the passwords came from, by its own name where it gave one. */
            val source: String,
            val added: Int,
            val updated: Int
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Read what the picker returned and work out what it would do to the vault. */
    fun open(resolver: ContentResolver, uri: Uri) {
        _state.value = State.Reading
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val name = displayName(resolver, uri)
                val bytes = readBytes(resolver, uri)
                    ?: return@withContext ImportFile.Result.Rejected(
                        "That file could not be read — it may be too large, or the app that holds " +
                            "it may have declined to hand it over."
                    )
                ImportFile.read(bytes, name)
            }

            _state.value = when (result) {
                is ImportFile.Result.Rejected -> State.Failed(result.reason)
                is ImportFile.Result.Understood -> {
                    val document = store.document.value
                    if (document == null) {
                        State.Failed("The vault shut while that file was being read. Open it and try again.")
                    } else {
                        val plan = VaultImport.plan(document, result.read)
                        State.Reviewing(plan, plan.defaultSelection)
                    }
                }
            }
        }
    }

    /**
     * Ask another credential manager on this phone to hand its vault over.
     *
     * This is the platform's own answer to the problem the rest of this screen works around. The
     * system shows every credential manager installed, the household picks one, **that** app asks
     * them to authenticate, and it returns its contents as a Credential Exchange payload — passkeys,
     * second factors, cards and custom fields included, none of which a CSV can carry. No file is
     * written at any point.
     *
     * [activityContext] must be the activity: the call puts a system selector on screen and
     * suspends until it resolves.
     *
     * The types asked for are everything this vault can hold. A provider hands over what it has of
     * them, and asking for a type nobody exports costs nothing, while not asking for one is how a
     * household silently loses their second factors in the one transfer designed to carry them.
     */
    fun importFromProvider(activityContext: Context) {
        _state.value = State.Reading
        viewModelScope.launch {
            val payload = try {
                ProviderEventsManager.create(activityContext)
                    .importCredentials(activityContext, ImportCredentialsRequest(TYPES, EXTENSIONS))
                    .response
                    .responseJson
            } catch (cancelled: ImportCredentialsCancellationException) {
                // Backing out of the selector is not a failure and must not be reported as one.
                _state.value = State.Idle
                return@launch
            } catch (failure: ImportCredentialsException) {
                _state.value = State.Failed(reasonFor(failure))
                return@launch
            }

            val document = store.document.value
            if (document == null) {
                _state.value = State.Failed(
                    "The vault shut while that transfer was happening. Open it and try again."
                )
                return@launch
            }

            val read = withContext(Dispatchers.Default) {
                CredentialExchange.read(payload, System.currentTimeMillis())
            }
            _state.value = if (read == null) {
                State.Failed(
                    "That app sent something this could not read. Its own export file will still " +
                        "work — the file picker below takes one."
                )
            } else {
                val plan = VaultImport.plan(document, read)
                State.Reviewing(plan, plan.defaultSelection)
            }
        }
    }

    /** What went wrong, as a sentence about what to do rather than an exception's name. */
    private fun reasonFor(failure: ImportCredentialsException): String = when (failure) {
        is ImportCredentialsNoExportOptionException ->
            "No password app on this phone offered to hand anything over. Not every one supports " +
                "this yet — the ones that do usually call it \"transfer\" or \"move passwords\". " +
                "Their export file works here either way."

        is ImportCredentialsProviderConfigurationException ->
            "This phone cannot do a direct transfer — it is a newer part of Android than the one " +
                "it is running, or the other app is not set up for it. An export file still works."

        is ImportCredentialsInvalidJsonException ->
            "The other app sent something this could not read. Its export file will still work."

        else ->
            "The transfer did not finish, and nothing was imported. It is safe to try again, and " +
                "an export file works if it keeps failing."
    }

    fun toggle(key: String) {
        val reviewing = _state.value as? State.Reviewing ?: return
        _state.value = reviewing.copy(
            selected = if (key in reviewing.selected) reviewing.selected - key else reviewing.selected + key
        )
    }

    /** Tick or untick every entry with this verdict — the only bulk action, and it is reversible. */
    fun setAll(verdict: VaultImport.Verdict, selected: Boolean) {
        val reviewing = _state.value as? State.Reviewing ?: return
        val keys = reviewing.plan.entries.filter { it.verdict == verdict }.map { it.key }
        _state.value = reviewing.copy(
            selected = if (selected) reviewing.selected + keys else reviewing.selected - keys.toSet()
        )
    }

    /**
     * Write the ticked entries — one save, and one re-sealing of the file, whether that is a single
     * item or six hundred.
     */
    fun confirm() {
        val reviewing = _state.value as? State.Reviewing ?: return
        viewModelScope.launch {
            var outcome: VaultImport.Outcome? = null
            val saved = store.mutate { document ->
                VaultImport.apply(document, reviewing.plan, reviewing.selected, System.currentTimeMillis())
                    .also { outcome = it }
                    .document
            }
            val result = outcome
            _state.value = if (!saved || result == null) {
                State.Failed(
                    "Nothing was imported: the vault could not be saved. It may have locked itself " +
                        "while the list was open, or the phone may be out of space."
                )
            } else {
                State.Done(reviewing.plan.sourceLabel, result.added, result.updated)
            }
        }
    }

    fun startOver() {
        _state.value = State.Idle
    }

    /**
     * Read the whole file, refusing at [ImportFile.MAX_BYTES] rather than at the end.
     *
     * A picked document can be anything — including a several-gigabyte file on a memory card, handed
     * over by an app that is perfectly happy to stream it. The ceiling is checked as it reads so that
     * a wrong pick costs a moment rather than the process.
     */
    private fun readBytes(resolver: ContentResolver, uri: Uri): ByteArray? = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > ImportFile.MAX_BYTES) return@use null
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }.getOrNull()

    /** The file's name, used only to describe it in a refusal. */
    private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    class Factory(private val store: VaultStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ImportViewModel(store) as T
    }

    private companion object {
        const val BUFFER = 8 * 1024

        /** Everything this vault can hold. See [importFromProvider] on why the list is long. */
        val TYPES = setOf(
            CredentialTypes.CREDENTIAL_TYPE_BASIC_AUTH,
            CredentialTypes.CREDENTIAL_TYPE_PUBLIC_KEY,
            CredentialTypes.CREDENTIAL_TYPE_TOTP,
            CredentialTypes.CREDENTIAL_TYPE_NOTE,
            CredentialTypes.CREDENTIAL_TYPE_CREDIT_CARD,
            CredentialTypes.CREDENTIAL_TYPE_API_KEY,
            CredentialTypes.CREDENTIAL_TYPE_WIFI,
            CredentialTypes.CREDENTIAL_TYPE_SSH_KEY,
            CredentialTypes.CREDENTIAL_TYPE_ADDRESS,
            CredentialTypes.CREDENTIAL_TYPE_CUSTOM_FIELDS,
            CredentialTypes.CREDENTIAL_TYPE_GENERATED_PASSWORD,
            CredentialTypes.CREDENTIAL_TYPE_IDENTITY_DOCUMENT,
            CredentialTypes.CREDENTIAL_TYPE_DRIVERS_LICENSE,
            CredentialTypes.CREDENTIAL_TYPE_PASSPORT,
            CredentialTypes.CREDENTIAL_TYPE_PERSON_NAME
        )

        /**
         * Items the other manager shares with somebody else.
         *
         * Asked for because a household vault is full of them — the streaming account, the wifi —
         * and a transfer that silently left out everything shared would be a transfer that looks
         * complete and is not.
         */
        val EXTENSIONS = setOf(KnownExtensions.KNOWN_EXTENSION_SHARED)
    }
}
