package com.secrets.app.ui.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.operations.vaultkit.AutofillMatch
import com.operations.vaultkit.VaultImport
import com.operations.vaultkit.VaultItem
import com.operations.vaultkit.VaultItemKind

/**
 * Bringing in the passwords that are somewhere else.
 *
 * ## The thing this screen is really for
 *
 * A password manager nobody has moved into is a password manager nobody uses, and the move is the
 * hard part: two hundred logins live in Chrome or in a subscription somebody is trying to leave, and
 * retyping them is a fortnight of evenings. So this reads the file those exporters already hand
 * their owner — no account, no network, no service talking to another service — and turns it into
 * items.
 *
 * ## Why it shows a list before it does anything
 *
 * Because the household knows something this app cannot work out: which copy is newer. A row that
 * matches nothing here is safe and arrives ticked. A row whose account is **already in the vault
 * with a different password** is not: the export may be from a browser last opened in March, and
 * taking it would overwrite a password changed in June with a dead one — losing, at the same time,
 * the only copy of the live one. Those are shown, counted, and left unticked. The replaced password
 * is kept either way ([VaultItem.history]), which is the safety net rather than the plan.
 *
 * ## And why it keeps mentioning the file
 *
 * Because for as long as that export exists, the household's entire password list is sitting in
 * plaintext in their Downloads folder, readable by anything with storage access, backed up by
 * whatever backs that folder up. This screen cannot delete it — it is not that app's file and this
 * app has no business reaching into shared storage — so it says so before the picker opens and
 * again on the way out.
 */
@Composable
fun ImportScreen(vm: ImportViewModel, onDone: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Any file: the exporters disagree about the type they claim, and a `.1pux` in particular
    // arrives as anything from `application/zip` to nothing at all. A filter that hid the file
    // somebody was told to pick would be worse than reading the wrong one, which is refused with a
    // sentence about what to pick instead.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.open(context.contentResolver, uri)
    }

    when (val current = state) {
        is ImportViewModel.State.Idle -> Introduction(onPick = { picker.launch(arrayOf("*/*")) })
        is ImportViewModel.State.Reading -> Reading()
        is ImportViewModel.State.Failed -> Failed(
            reason = current.reason,
            onRetry = { picker.launch(arrayOf("*/*")) },
            onBack = onDone
        )
        is ImportViewModel.State.Reviewing -> Review(
            state = current,
            onToggle = vm::toggle,
            onSetAll = vm::setAll,
            onConfirm = vm::confirm,
            onCancel = vm::startOver
        )
        is ImportViewModel.State.Done -> Done(state = current, onAgain = vm::startOver, onDone = onDone)
    }
}

@Composable
private fun Introduction(onPick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Where your passwords are now", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "This reads the file your browser or 1Password gives you when you ask " +
                        "for your passwords back. Nothing is sent anywhere and nothing signs in to " +
                        "anything — this app cannot reach the network at all. It reads a file you " +
                        "already have.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Text("Where to find it", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                SOURCES.forEach { (where, how) ->
                    Spacer(Modifier.height(6.dp))
                    Text(where, style = MaterialTheme.typography.labelLarge)
                    Text(
                        how,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Delete the file afterwards", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "An export is every password you have, in plain text, in your Downloads " +
                        "folder — readable by anything on the phone with storage access, and carried " +
                        "into whatever backs that folder up. It is safe for the minute it takes to " +
                        "import and it is a liability after that. This app cannot delete it for you: " +
                        "it is not this app's file, and a vault that could reach into shared storage " +
                        "would be a vault with a reason to be nervous about.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Button(onClick = onPick) { Text("Choose the file") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Reading() {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Reading it…", style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Failed(reason: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Nothing was imported", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(reason, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry) { Text("Choose another file") }
            TextButton(onClick = onBack) { Text("Back") }
        }
    }
}

/**
 * The list, with the decision on it.
 *
 * Three groups, in the order somebody reads them: what is new, what would be changed, and what is
 * already here. The third is shown rather than hidden — "214 read, 198 imported" with no account of
 * the other sixteen is how an import gets a reputation for losing things — and it is not selectable,
 * because writing an item identical to the one in the vault would tell the audit its password was
 * changed today.
 *
 * No password is on this screen. Not the incoming one, not the one it would replace: this is a list
 * of *accounts*, and a screen that showed two hundred passwords in the clear would be the one place
 * in the app where a shoulder is worth more than the passphrase.
 */
@Composable
private fun Review(
    state: ImportViewModel.State.Reviewing,
    onToggle: (String) -> Unit,
    onSetAll: (VaultImport.Verdict, Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val plan = state.plan
    val fresh = plan.entries.filter { it.verdict == VaultImport.Verdict.NEW }
    val changed = plan.entries.filter { it.verdict == VaultImport.Verdict.CHANGED }
    val known = plan.entries.filter { it.verdict == VaultImport.Verdict.ALREADY_HERE }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Read ${plan.entries.size} from ${plan.format.label}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Nothing has been written yet. Tick what you want and the rest " +
                                "stays where it is.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (fresh.isNotEmpty()) {
                item {
                    GroupHeader(
                        title = "New — ${fresh.size}",
                        body = "Nothing in the vault is filed under these.",
                        onAll = { onSetAll(VaultImport.Verdict.NEW, true) },
                        onNone = { onSetAll(VaultImport.Verdict.NEW, false) }
                    )
                }
                items(fresh, key = { it.key }) { entry ->
                    EntryRow(entry, entry.key in state.selected) { onToggle(entry.key) }
                }
            }

            if (changed.isNotEmpty()) {
                item {
                    GroupHeader(
                        title = "Already here, with a different password — ${changed.size}",
                        body = "Unticked, because the export may be the older copy. Taking one " +
                            "keeps the item exactly as it is here — its tags, its fields, its " +
                            "second factor — and changes the password, keeping the one it replaced.",
                        onAll = { onSetAll(VaultImport.Verdict.CHANGED, true) },
                        onNone = { onSetAll(VaultImport.Verdict.CHANGED, false) }
                    )
                }
                items(changed, key = { it.key }) { entry ->
                    EntryRow(entry, entry.key in state.selected) { onToggle(entry.key) }
                }
            }

            if (known.isNotEmpty()) {
                item {
                    GroupHeader(
                        title = "Already in the vault — ${known.size}",
                        body = "The same account with the same password. Nothing to do."
                    )
                }
                items(known, key = { it.key }) { entry -> EntryRow(entry, null) {} }
            }

            if (plan.skipped.isNotEmpty()) {
                item { SkippedCard(plan.skipped) }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onConfirm, enabled = state.ready) {
                Text("Import ${state.selected.size}")
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun GroupHeader(
    title: String,
    body: String,
    onAll: (() -> Unit)? = null,
    onNone: (() -> Unit)? = null
) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (onAll != null) TextButton(onClick = onAll) { Text("All") }
            if (onNone != null) TextButton(onClick = onNone) { Text("None") }
        }
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One account. [selected] is null for a row there is nothing to decide about. */
@Composable
private fun EntryRow(entry: VaultImport.Entry, selected: Boolean?, onToggle: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.candidate.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleSmall
                )
                subtitle(entry.candidate)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                extras(entry.candidate)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            if (selected != null) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() })
            }
        }
    }
}

@Composable
private fun SkippedCard(skipped: List<VaultImport.Skipped>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Left out — ${skipped.size}", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Listed rather than counted, so nothing goes missing quietly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            skipped.take(SKIPPED_SHOWN).forEach {
                Spacer(Modifier.height(6.dp))
                Text("${it.what} — ${it.why}", style = MaterialTheme.typography.bodySmall)
            }
            if (skipped.size > SKIPPED_SHOWN) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "and ${skipped.size - SKIPPED_SHOWN} more",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun Done(state: ImportViewModel.State.Done, onAgain: () -> Unit, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("In the vault", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        state.updated == 0 -> "${state.added} added from ${state.format.label}."
                        state.added == 0 -> "${state.updated} updated from ${state.format.label}."
                        else -> "${state.added} added and ${state.updated} updated from " +
                            "${state.format.label}."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Now delete the export. It is every password you have, in plain text, " +
                        "wherever the picker found it — and it does not get safer by being forgotten.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Worth a look at Check: an import is the one moment a vault gains " +
                        "hundreds of passwords nobody has looked at in years, and that screen says " +
                        "which are weak, reused or old.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDone) { Text("Done") }
            OutlinedButton(onClick = onAgain) { Text("Import another file") }
        }
    }
}

/** The username, or the address when there is no username to show. */
private fun subtitle(item: VaultItem): String? = when {
    item.username.isNotBlank() -> item.username
    else -> AutofillMatch.hostOf(item.url)
}

/** What came with it that a row would otherwise not show: the address, a seed, a history, fields. */
private fun extras(item: VaultItem): String? {
    val host = AutofillMatch.hostOf(item.url)?.takeIf { item.username.isNotBlank() }
    val parts = listOfNotNull(
        host,
        item.kind.label.takeIf { item.kind != VaultItemKind.LOGIN },
        "second factor".takeIf { item.hasTotp },
        "${item.fields.size} fields".takeIf { item.fields.isNotEmpty() },
        "${item.history.size} previous".takeIf { item.history.isNotEmpty() }
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** Where each export lives, in the words its own menus use. */
private val SOURCES = listOf(
    "Chrome, Edge or Brave" to
        "Settings → Passwords → the three dots → Export passwords. You get a .csv.",
    "Firefox" to
        "about:logins → the three dots → Export logins. You get a .csv.",
    "Safari or Apple Passwords" to
        "Passwords app → File → Export all passwords. You get a .csv.",
    "1Password" to
        "The desktop app → your account → Export → the .1pux file, which keeps the cards, the " +
            "notes, the custom fields and the password history. Its .csv works too and keeps less."
)

private const val SKIPPED_SHOWN = 20
