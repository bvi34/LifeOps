package com.secrets.app.autofill

import android.app.PendingIntent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import com.operations.vaultkit.AutofillMatch
import com.operations.vaultkit.VaultItem
import com.operations.vaultkit.VaultState
import com.secrets.app.R
import com.secrets.app.SecretsApp
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one component in this module the outside world can reach — and what it is allowed to say.
 *
 * ## What changed, and what did not
 *
 * This module's manifest used to declare every component `exported="false"` and say so as a promise.
 * An `AutofillService` cannot be one of those: the system binds it, and the system can only bind
 * something exported. What keeps that from being a hole is the permission on the declaration —
 * `BIND_AUTOFILL_SERVICE` is held by the platform and by nothing else, so "exported" here means
 * reachable by the operating system and still not by any app on the phone. It is also inert until
 * somebody picks this app as their autofill service in system settings, which is a deliberate act
 * in a screen this app does not control.
 *
 * ## The shape of an answer
 *
 * Three cases, and the difference between them matters more than the code does:
 *
 *  - **No vault, or a locked one.** Nothing can be read, so nothing is offered *except* a way in:
 *    one entry that says Secrets is locked and, when tapped, opens the unlock screen and comes back
 *    with the real answer. It cannot know whether the vault holds anything for this form — that is
 *    what being locked means — so it offers the entry whenever the form could take a credential.
 *  - **Unlocked, with matches.** A row per match, titled and subtitled so the person can see which
 *    one they are choosing before they choose it.
 *  - **Unlocked, with none.** No rows of its own, and one entry that opens the vault's own list to
 *    be searched. The rule in `AutofillMatch` is that a match has to be earned; offering the whole
 *    vault to an unrecognised app in a dropdown would abandon it. Offering a *door* to the vault,
 *    which the person then walks through and picks from, does not: the choosing is theirs.
 *
 * ## What it will not do
 *
 * It will not fill this suite's own package — the vault's unlock box is a password field like any
 * other, and a vault that offers to fill its own passphrase is a vault whose passphrase is in
 * itself. And it will never offer a **managed credential**; that rule is in `AutofillMatch`, with
 * the rest of the matching, because it is a rule about secrets rather than about Android.
 */
class SecretsAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) {
            callback.onSuccess(null)
            return
        }

        val form = AutofillForm.parse(structure)
        if (!form.fillable) {
            callback.onSuccess(null)
            return
        }

        // Never our own package. The unlock screen's passphrase box is a password field, and a
        // vault that offers to fill it from itself is a vault with its own key inside it.
        if (form.packageName == packageName) {
            callback.onSuccess(null)
            return
        }

        val app = SecretsApp.get(this)
        val response = FillResponse.Builder()

        if (app.vault.state.value != VaultState.UNLOCKED) {
            response.setAuthentication(
                form.ids,
                authIntentSender(form, AutofillAuthActivity.MODE_UNLOCK),
                AutofillDatasets.presentation(
                    AutofillDatasets.row(
                        this,
                        getString(R.string.secrets_autofill_locked),
                        getString(R.string.secrets_autofill_locked_detail)
                    )
                )
            )
        } else {
            val document = app.vault.document.value
            val matches = AutofillMatch.candidates(
                items = document?.items.orEmpty(),
                packageName = form.packageName,
                webDomain = form.webDomain
            )

            if (matches.isEmpty()) {
                // A door rather than a drawer: see the class note.
                response.setAuthentication(
                    form.ids,
                    authIntentSender(form, AutofillAuthActivity.MODE_PICK),
                    AutofillDatasets.presentation(
                        AutofillDatasets.row(
                            this,
                            getString(R.string.secrets_autofill_choose),
                            getString(R.string.secrets_autofill_choose_detail)
                        )
                    )
                )
            } else {
                matches.forEach { candidate ->
                    AutofillDatasets.build(this, form, candidate.item)?.let(response::addDataset)
                }
            }
        }

        saveInfo(form)?.let(response::setSaveInfo)

        callback.onSuccess(runCatching { response.build() }.getOrNull())
    }

    /**
     * A sign-in that was typed by hand, offered to the vault.
     *
     * Only ever *adds*: a form filled in with a password the vault already holds is not a change to
     * record, and a form filled in with a different one is more likely a second account than a
     * rotation somebody wants overwritten. Guessing wrongly here would silently replace a working
     * password, which is the one thing this app must not do by accident.
     */
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) {
            callback.onFailure(getString(R.string.secrets_autofill_save_unreadable))
            return
        }

        val app = SecretsApp.get(this)
        if (app.vault.state.value != VaultState.UNLOCKED) {
            // There is no queue for this. The pending-write queue in SecretsAccess exists for an
            // app's own mirrored credential, which the app still holds and can re-file; a sign-in
            // typed into somebody else's form exists nowhere else, and holding it in memory until
            // an unlock that may never come would be pretending to have saved it.
            callback.onFailure(getString(R.string.secrets_autofill_save_locked))
            return
        }

        val form = AutofillForm.parse(structure)
        val values = AutofillValues.read(structure, form)
        val password = values.password
        if (password.isNullOrEmpty()) {
            callback.onFailure(getString(R.string.secrets_autofill_save_nothing))
            return
        }

        val document = app.vault.document.value
        val host = AutofillMatch.hostOf(form.webDomain)
        val alreadyHere = document?.live?.any {
            !it.isManaged && it.secret == password && it.username == values.username.orEmpty()
        } == true
        if (alreadyHere) {
            callback.onSuccess()
            return
        }

        val now = System.currentTimeMillis()
        val saved = app.vault.mutateBlocking { current ->
            current.upsert(
                VaultItem(
                    id = UUID.randomUUID().toString(),
                    title = host ?: form.packageName.orEmpty().ifEmpty { "Saved sign-in" },
                    username = values.username.orEmpty(),
                    secret = password,
                    url = host.orEmpty(),
                    createdAt = now,
                    updatedAt = now
                ),
                now
            )
        }

        if (saved) callback.onSuccess() else callback.onFailure(getString(R.string.secrets_autofill_save_failed))
    }

    private fun saveInfo(form: AutofillForm.Parsed): SaveInfo? {
        if (!form.savable) return null
        val required = listOfNotNull(form.passwordId).toTypedArray()
        val builder = SaveInfo.Builder(
            SaveInfo.SAVE_DATA_TYPE_PASSWORD or SaveInfo.SAVE_DATA_TYPE_USERNAME,
            required
        )
        form.usernameId?.let { builder.setOptionalIds(arrayOf(it)) }
        return builder.build()
    }

    private fun authIntentSender(form: AutofillForm.Parsed, mode: String) =
        PendingIntent.getActivity(
            this,
            REQUESTS.incrementAndGet(),
            AutofillAuthActivity.intent(this, form, mode),
            // Mutable because the platform adds its own extras to this intent on the way out; an
            // immutable one is rejected outright.
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
        ).intentSender

    private companion object {
        /** Request codes, so two forms in flight do not share a PendingIntent and its extras. */
        val REQUESTS = AtomicInteger(0)
    }
}

/** The values already typed into a form, for a save request. */
internal object AutofillValues {

    data class Typed(val username: String?, val password: String?)

    fun read(structure: android.app.assist.AssistStructure, form: AutofillForm.Parsed): Typed {
        var username: String? = null
        var password: String? = null

        fun visit(node: android.app.assist.AssistStructure.ViewNode) {
            val id = node.autofillId
            if (id != null) {
                val text = node.autofillValue?.takeIf { it.isText }?.textValue?.toString()
                    ?: node.text?.toString()
                if (!text.isNullOrEmpty()) {
                    when (id) {
                        form.usernameId -> username = text
                        form.passwordId -> password = text
                    }
                }
            }
            for (i in 0 until node.childCount) visit(node.getChildAt(i))
        }

        for (i in 0 until structure.windowNodeCount) {
            structure.getWindowNodeAt(i).rootViewNode?.let(::visit)
        }
        return Typed(username = username, password = password)
    }
}
