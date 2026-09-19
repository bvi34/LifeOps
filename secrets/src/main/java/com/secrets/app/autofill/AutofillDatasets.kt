package com.secrets.app.autofill

import android.content.Context
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.Presentations
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
    fun build(context: Context, form: AutofillForm.Parsed, item: VaultItem): Dataset? {
        val builder = Dataset.Builder(presentation(context, item))
        var filled = false

        fun fill(id: android.view.autofill.AutofillId, value: String) {
            builder.setField(id, Field.Builder().setValue(AutofillValue.forText(value)).build())
            filled = true
        }

        form.usernameId?.let { id -> if (item.username.isNotEmpty()) fill(id, item.username) }
        form.passwordId?.let { id -> if (item.secret.isNotEmpty()) fill(id, item.secret) }
        form.otpId?.let { id ->
            // The code, not the seed — computed now, which is the only form of it that is any use
            // in a text box, and which stops working on its own half a minute later.
            item.totp?.let { config ->
                Totp.code(config, System.currentTimeMillis())?.let { code -> fill(id, code.digits) }
            }
        }

        // A dataset that fills nothing is worse than no dataset: the person has already decided to
        // trust the row by the time they find out it does nothing. Fields this item has no value
        // for are simply left out — a dataset is not required to cover every field in the form.
        if (!filled) return null
        return runCatching { builder.build() }.getOrNull()
    }

    /**
     * The dropdown row, wrapped for the API this floor allows.
     *
     * `Presentations` arrived at API 33 and replaced handing a bare `RemoteViews` to the builder.
     * Below 33 there was no choice; at a floor of 34 there is no reason to use the older shape, and
     * mixing the two on one builder is not allowed — `setValue` and `setField` cannot both be used,
     * so this is all-or-nothing and it is the newer half.
     */
    fun presentation(context: Context, item: VaultItem): Presentations =
        presentation(row(context, item.title.ifBlank { "Untitled" }, subtitle(context, item)))

    fun presentation(row: RemoteViews): Presentations =
        Presentations.Builder().setMenuPresentation(row).build()

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
