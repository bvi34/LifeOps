package com.secrets.app.passkey

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CredentialEntry
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.operations.vaultkit.PasskeyRequest
import com.operations.vaultkit.VaultItem
import java.util.concurrent.atomic.AtomicInteger

/**
 * Which stored passkeys answer a request, and what the system shows for each.
 *
 * Shared by the service and by the screen that answers after an unlock, because both produce the
 * same list and the one that drifted would be the one nobody noticed — the service's list is what a
 * locked phone never shows, and the screen's is what it shows a second later.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal object PasskeyEntries {

    /**
     * Which stored passkeys answer [asked].
     *
     * The relying party must match **exactly** — not by subdomain, not by anything clever. A
     * passkey's signature is scoped to the `rpId` it was created under, so offering a credential to
     * a different one produces a signature that site rejects; the looseness that makes sense for a
     * password would be a broken sign-in rather than a convenience. It is also why nothing here
     * resembles `AutofillMatch`: a passkey names its site, the request names its site, and they
     * either agree or they do not. The ambiguity that made that file dangerous does not exist.
     *
     * An empty `allowCredentials` is the discoverable-credential flow — "whatever you hold for this
     * site" — which is how passkeys are normally used.
     */
    fun matching(items: List<VaultItem>, asked: PasskeyRequest.Assertion): List<VaultItem> =
        items.filter { item ->
            val passkey = item.passkey ?: return@filter false
            if (!passkey.isUsable) return@filter false
            if (!passkey.rpId.equals(asked.rpId, ignoreCase = true)) return@filter false
            asked.allowCredentialIds.isEmpty() ||
                asked.allowCredentialIds.any { PasskeyRequest.sameCredential(it, passkey.credentialId) }
        }

    /** One entry per passkey that answers any of [request]'s options. */
    fun forRequest(
        context: Context,
        request: BeginGetCredentialRequest,
        items: List<VaultItem>
    ): List<CredentialEntry> {
        val entries = ArrayList<CredentialEntry>()
        for (option in request.beginGetCredentialOptions) {
            if (option !is BeginGetPublicKeyCredentialOption) continue
            val asked = PasskeyRequest.parseAssertion(option.requestJson) ?: continue
            for (item in matching(items, asked)) {
                entry(context, item, option)?.let(entries::add)
            }
        }
        return entries
    }

    private fun entry(
        context: Context,
        item: VaultItem,
        option: BeginGetPublicKeyCredentialOption
    ): CredentialEntry? {
        val passkey = item.passkey ?: return null
        return runCatching {
            PublicKeyCredentialEntry.Builder(
                context = context,
                username = passkey.userName
                    .ifBlank { passkey.userDisplayName }
                    .ifBlank { passkey.rpId },
                pendingIntent = pendingIntent(context, PasskeyActivity.ACTION_GET, item.id),
                beginGetPublicKeyCredentialOption = option
            )
                .setDisplayName(item.title.ifBlank { passkey.rpId })
                .build()
        }.getOrNull()
    }

    fun pendingIntent(context: Context, action: String, itemId: String?): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUESTS.incrementAndGet(),
            Intent(context, PasskeyActivity::class.java).apply {
                setAction(action)
                itemId?.let { putExtra(PasskeyActivity.EXTRA_ITEM_ID, it) }
            },
            // Mutable because Credential Manager puts the request itself into this intent on the way
            // out; an immutable one arrives at the activity carrying nothing.
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
        )

    /** Request codes, so two entries in one dialog do not share a PendingIntent and its extras. */
    private val REQUESTS = AtomicInteger(0)
}
