package com.secrets.app.autofill

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.operations.suite.ui.fields.SuiteTextField
import com.operations.vaultkit.AutofillMatch
import com.operations.vaultkit.VaultItem
import com.operations.vaultkit.VaultSearch
import com.operations.vaultkit.VaultState
import com.secrets.app.SecretsApp
import com.secrets.app.ui.theme.SecretsTheme
import com.secrets.app.ui.unlock.UnlockScreen
import com.secrets.app.ui.unlock.UnlockViewModel

/**
 * The screen behind an autofill entry that needs a person before it can answer.
 *
 * Two reasons the system ends up here, and they are the two the service cannot answer by itself:
 *
 *  - **The vault is shut.** Nothing can be read while it is, so the dropdown offered a way in
 *    rather than an answer. This unlocks — passphrase, or the device shortcut if it is set up —
 *    and then hands back the real datasets, so the dropdown fills in behind it.
 *  - **Nothing matched.** `AutofillMatch` refuses to guess, deliberately, so an unrecognised app
 *    gets no rows. What it gets instead is this: the vault's own list, searchable, where the person
 *    picks the item themselves. That is a different act from a service deciding on their behalf,
 *    and it is the only form of "fill anything anywhere" this app will do.
 *
 * It is **not exported**. The system starts it through a `PendingIntent` this app created, which
 * carries this app's identity rather than the caller's — so the one component the outside world can
 * reach stays the service, and this stays as unreachable as every other screen here.
 *
 * `FLAG_SECURE`, like the main activity and for the same reason: what is on screen at the end of
 * this is a list of the household's logins.
 */
class AutofillAuthActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val form = formFromIntent()
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_UNLOCK
        val app = SecretsApp.get(this)

        // A cancelled authentication has to leave a result behind, or the system is left holding a
        // dropdown that never resolves. Set the refusal up front and overwrite it on success.
        setResult(RESULT_CANCELED)

        setContent {
            SecretsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vaultState by app.vault.state.collectAsStateWithLifecycle()
                    val document by app.vault.document.collectAsStateWithLifecycle()
                    var picking by remember { mutableStateOf(mode == MODE_PICK) }

                    if (vaultState != VaultState.UNLOCKED) {
                        val vm: UnlockViewModel = viewModel(factory = UnlockViewModel.Factory(app.vault))
                        UnlockScreen(vm = vm, onOpened = { })
                    } else {
                        val matches = remember(document, picking) {
                            if (picking) emptyList() else {
                                AutofillMatch.candidates(
                                    items = document?.items.orEmpty(),
                                    packageName = form.packageName,
                                    webDomain = form.webDomain
                                ).map { it.item }
                            }
                        }

                        // The vault opened and it does have something for this form: answer and
                        // get out of the way. Nobody asked to browse — they asked to sign in.
                        LaunchedEffect(matches) {
                            if (matches.isNotEmpty()) {
                                answerWith(datasetsFor(form, matches))
                            } else {
                                picking = true
                            }
                        }

                        if (picking) {
                            PickList(
                                items = document?.live.orEmpty().filterNot { it.isManaged },
                                onPick = { item ->
                                    AutofillDatasets.build(this@AutofillAuthActivity, form, item)
                                        ?.let(::answerWith)
                                        ?: finish()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * A whole [android.service.autofill.FillResponse] rather than one dataset, because the request
     * being answered authenticated over the form as a whole: handing back several rows lets the
     * dropdown show them and lets the person choose, which is what would have happened had the
     * vault been open when they tapped the field.
     */
    private fun datasetsFor(form: AutofillForm.Parsed, items: List<VaultItem>): Parcelable {
        val response = android.service.autofill.FillResponse.Builder()
        var any = false
        items.forEach { item ->
            AutofillDatasets.build(this, form, item)?.let {
                response.addDataset(it)
                any = true
            }
        }
        if (!any) {
            // Everything matched and nothing could fill, which means the matches held only values
            // this form has no box for. Better an empty answer than a row that does nothing.
            return android.service.autofill.FillResponse.Builder().build()
        }
        return response.build()
    }

    private fun answerWith(result: Parcelable) {
        setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, result))
        finish()
    }

    private fun formFromIntent() = AutofillForm.Parsed(
        packageName = intent.getStringExtra(EXTRA_PACKAGE),
        webDomain = intent.getStringExtra(EXTRA_DOMAIN),
        usernameId = autofillId(EXTRA_USERNAME_ID),
        passwordId = autofillId(EXTRA_PASSWORD_ID),
        otpId = autofillId(EXTRA_OTP_ID)
    )

    @Suppress("DEPRECATION")
    private fun autofillId(key: String): AutofillId? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(key, AutofillId::class.java)
        } else {
            intent.getParcelableExtra(key)
        }

    companion object {
        const val MODE_UNLOCK = "unlock"
        const val MODE_PICK = "pick"

        private const val EXTRA_MODE = "mode"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_DOMAIN = "domain"
        private const val EXTRA_USERNAME_ID = "username_id"
        private const val EXTRA_PASSWORD_ID = "password_id"
        private const val EXTRA_OTP_ID = "otp_id"

        fun intent(context: Context, form: AutofillForm.Parsed, mode: String): Intent =
            Intent(context, AutofillAuthActivity::class.java).apply {
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_PACKAGE, form.packageName)
                putExtra(EXTRA_DOMAIN, form.webDomain)
                putExtra(EXTRA_USERNAME_ID, form.usernameId)
                putExtra(EXTRA_PASSWORD_ID, form.passwordId)
                putExtra(EXTRA_OTP_ID, form.otpId)
            }
    }
}

/**
 * The vault's own list, cut down to what this screen is for.
 *
 * Mirrored credentials are not in it: an app's access token is not a sign-in, and this list exists
 * to be typed into somebody else's form. The search is `VaultSearch`, so it obeys the same rule the
 * main list does and never matches a secret.
 */
@Composable
private fun PickList(items: List<VaultItem>, onPick: (VaultItem) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(items, query) { VaultSearch.search(items, query) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Choose a sign-in", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Nothing in the vault is filed under this app or page, so nothing was offered " +
                "automatically. Pick one and it will be filled in.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        SuiteTextField(
            label = "Search",
            value = query,
            onValueChange = { query = it },
            capitalise = KeyboardCapitalization.None,
            leading = { Icon(Icons.Filled.Search, contentDescription = null) }
        )
        Spacer(Modifier.height(8.dp))

        if (shown.isEmpty()) {
            Text(
                text = if (items.isEmpty()) "The vault is empty." else "Nothing matches.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onPick(item) }
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                item.title.ifBlank { "Untitled" },
                                style = MaterialTheme.typography.titleSmall
                            )
                            val subtitle = listOfNotNull(
                                item.username.takeIf { it.isNotBlank() },
                                AutofillMatch.hostOf(item.url)
                            ).joinToString(" · ")
                            if (subtitle.isNotBlank()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
