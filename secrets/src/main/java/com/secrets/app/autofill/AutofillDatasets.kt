package com.secrets.app.autofill

import android.content.Context
import android.service.autofill.Dataset
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.operations.vaultkit.AutofillMatch
import com.operations.vaultkit.Totp
import com.operations.vaultkit.VaultItem
import com.secrets.app.R

/**
 * One vault item, turned into a row the system can show and a set of values it can type.
 *
 * Shared by the service and by the screen that answers an authenticated request, because both build
 * exactly the same thing and the one that got it subtly different would be the one nobody tested.
 */
internal object AutofillDatasets {

    /**
     * A dataset for [item], or null if it cannot fill anything this form asked for.
     *
     * Returning null rather than an empty dataset matters: a row in the dropdown that does nothing
     * when tapped is worse than no row, because the person has already decided to trust it by then.
     */
    // RemoteViews presentations were deprecated at API 33 in favour of `Presentations`, which does
    // not exist below it. This module's floor is 26, so the deprecated calls are the only ones that
    // work everywhere it runs; swapping them for a version-gated pair would mean two code paths for
    // a dropdown row. Revisit when the floor moves past 33.
    @Suppress("DEPRECATION")
    fun build(context: Context, form: AutofillForm.Parsed, item: VaultItem): Dataset? {
        val builder = Dataset.Builder(row(context, item.title.ifBlank { "Untitled" }, subtitle(context, item)))
        var filled = false

        form.usernameId?.let { id ->
            if (item.username.isNotEmpty()) {
                builder.setValue(id, AutofillValue.forText(item.username))
                filled = true
            }
        }
        form.passwordId?.let { id ->
            if (item.secret.isNotEmpty()) {
                builder.setValue(id, AutofillValue.forText(item.secret))
                filled = true
            }
        }
        form.otpId?.let { id ->
            // The code, not the seed — computed now, which is the only form of it that is any use
            // in a text box, and which stops working on its own half a minute later.
            item.totp?.let { config ->
                Totp.code(config, System.currentTimeMillis())?.let { code ->
                    builder.setValue(id, AutofillValue.forText(code.digits))
                    filled = true
                }
            }
        }

        if (!filled) return null

        // Every id the response authenticates over needs a value or the system drops the dataset;
        // the ones this item has nothing for are set to null explicitly rather than left out.
        form.ids.forEach { id ->
            runCatching { builder.setValue(id, null as AutofillValue?) }
        }
        return runCatching { builder.build() }.getOrNull()
    }

    fun row(context: Context, title: String, subtitle: String): RemoteViews =
        RemoteViews(context.packageName, R.layout.secrets_autofill_row).apply {
            setTextViewText(R.id.autofill_title, title)
            setTextViewText(R.id.autofill_subtitle, subtitle)
        }

    /**
     * The line under the title — which account, on which site, and whether this row also carries the
     * second factor. It is what lets somebody see which of three logins they are about to use before
     * they use it.
     */
    fun subtitle(context: Context, item: VaultItem): String {
        val parts = buildList {
            item.username.takeIf { it.isNotBlank() }?.let(::add)
            AutofillMatch.hostOf(item.url)?.let(::add)
            if (item.hasTotp) add(context.getString(R.string.secrets_autofill_has_code))
        }
        return parts.joinToString(" · ").ifEmpty { item.kind.label }
    }
}
