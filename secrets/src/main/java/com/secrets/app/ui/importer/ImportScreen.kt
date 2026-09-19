package com.secrets.app.ui.importer

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
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
 * plaintext wherever it was saved, readable by anything with storage access, backed up by whatever
 * backs that folder up. This screen cannot delete it — it is not that app's file and this app has
 * no business reaching into shared storage — so it says so before the picker opens and again on the
 * way out.
 *
 * The better answer is for the file never to exist, which is what [ImportShareActivity] is for: an
 * export sent straight from the share sheet is read out of the stream the sharing app opened and is
 * never saved anywhere. The picker below stays for every export made on a computer and carried
 * across, where there is a file whatever anybody prefers.
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
        is ImportViewModel.State.Idle -> Introduction(
            onPick = { picker.launch(arrayOf("*/*")) },
            onTransfer = { vm.importFromProvider(context) }
        )
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

/**
 * Pick where the passwords are now, then read how to get them out of it.
 *
 * A list of sources rather than a bare "choose a file" for one reason: the step that loses people is
 * not this screen, it is the twenty minutes in the other app looking for a menu item called Export.
 * Naming the menu items is most of the help this app can give, since the alternative — signing in to
 * the other manager and pulling everything across — is a door nobody on that side has built (the
 * reasoning is in [ImportSource]).
 */
@Composable
private fun Introduction(onPick: () -> Unit, onTransfer: () -> Unit) {
    var chosen by remember { mutableStateOf<ImportSource?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val source = chosen
        if (source == null) {
            TransferCard(onTransfer = onTransfer)
            SourceList(onChoose = { chosen = it })
        } else {
            SourceSteps(source = source, onBack = { chosen = null }, onPick = onPick)
        }

        ExportWarningCard()
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The direct route: ask another credential manager to hand its vault over.
 *
 * First on the screen because it is better than everything under it in every way that matters — it
 * carries passkeys, second factors, cards and custom fields, it authenticates against the other app
 * rather than against a file, and it never puts a plaintext copy of anything on disk. It is not
 * first *only* because not every manager implements it yet, which is why the file routes stay
 * below rather than behind a "more options".
 */
@Composable
private fun TransferCard(onTransfer: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Straight from another app", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Android can move credentials between password apps on this phone. You pick " +
                    "the app, it asks you to unlock it, and it hands everything over — passkeys, " +
                    "second factors, cards and custom fields included, which no exported file " +
                    "carries. Nothing is written to a file at any point.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "It copies rather than moves: the other app still has everything until you " +
                    "delete it there.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onTransfer) { Text("Choose an app") }
        }
    }
}

@Composable
private fun SourceList(onChoose: (ImportSource) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Or from an exported file", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "For an app that does not offer the transfer above, or passwords kept on a " +
                    "computer. This reads the file that app gives you when you ask for your " +
                    "passwords back — nothing is sent anywhere and nothing signs in to anything, " +
                    "because this app cannot reach the network at all.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    ImportSource.entries.forEach { source ->
        Card(
            modifier = Modifier.fillMaxWidth().clickable { onChoose(source) }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(source.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        source.holds,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (source.onDevice) {
                    Text(
                        "on this phone",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceSteps(source: ImportSource, onBack: () -> Unit, onPick: () -> Unit) {
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(source.label, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                source.whereItIsMade,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            source.steps.forEachIndexed { index, step ->
                Spacer(Modifier.height(8.dp))
                Row {
                    Text(
                        "${index + 1}.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(step, style = MaterialTheme.typography.bodyMedium)
                }
            }

            source.web?.let { address ->
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = {
                    // Best effort, like every other place this app leaves for software nobody here
                    // chose: a phone with nothing to open a web address fails to resolve rather
                    // than taking the vault down with it.
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)))
                    }
                }) { Text("Open the password manager") }
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Then send it here", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "When the export's share sheet appears, pick Secrets and it lands straight " +
                    "on the review list. If the file is already on this phone, choose it instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPick) { Text("Choose the file") }
                TextButton(onClick = onBack) { Text("Somewhere else") }
            }
        }
    }
}

/**
 * The export file, which is the dangerous part of this whole feature.
 *
 * Shown on the way in and again on the way out, because it is the step everybody skips and the only
 * one with a lasting cost.
 */
@Composable
private fun ExportWarningCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Delete the file afterwards", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "An export is every password you have, in plain text — readable by anything " +
                    "on the phone with storage access, and carried into whatever backs that folder " +
                    "up. It is safe for the minute it takes to import and it is a liability after " +
                    "that. This app cannot delete it for you: it is not this app's file, and a " +
                    "vault that could reach into shared storage would be a vault worth being " +
                    "nervous about.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
/**
 * The list, with the decisions on it.
 *
 * One section per verdict, in the order somebody reads them, and every section says what taking it
 * would do. The three in the middle are the interesting ones: an account already here with a
 * *different* password is the hard case of any import, and rather than asking four hundred times or
 * overwriting silently, the plan resolves what it can from evidence and shows its reasoning — this
 * copy is dated later, this one earlier, these two cannot be told apart. Only the last is left
 * unticked, because it is the only one where a wrong guess costs a working password.
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
                            "Read ${plan.entries.size} from ${plan.sourceLabel}",
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

            for (section in SECTIONS) {
                val entries = plan.entries.filter { it.verdict == section.verdict }
                if (entries.isEmpty()) continue

                item(key = "head-${section.verdict}") {
                    GroupHeader(
                        title = "${section.title} — ${entries.size}",
                        body = section.body,
                        onAll = if (section.selectable) {
                            { onSetAll(section.verdict, true) }
                        } else {
                            null
                        },
                        onNone = if (section.selectable) {
                            { onSetAll(section.verdict, false) }
                        } else {
                            null
                        }
                    )
                }
                items(entries, key = { it.key }) { entry ->
                    EntryRow(
                        entry = entry,
                        selected = if (section.selectable) entry.key in state.selected else null,
                        onToggle = { onToggle(entry.key) }
                    )
                }
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

/** A heading and its list, per verdict. The order is the order they are read. */
private data class Section(
    val verdict: VaultImport.Verdict,
    val title: String,
    val body: String,
    val selectable: Boolean = true
)

private val SECTIONS = listOf(
    Section(
        verdict = VaultImport.Verdict.NEW,
        title = "New",
        body = "Nothing in the vault is filed under these."
    ),
    Section(
        verdict = VaultImport.Verdict.ADDS,
        title = "A passkey or second factor to add",
        body = "The same password as the one here, plus something this vault does not have. " +
            "Nothing is overwritten."
    ),
    Section(
        verdict = VaultImport.Verdict.REPLACES,
        title = "Newer than the copy here",
        body = "Dated after the password in this vault — or the export itself lists that password " +
            "as an old one. Taking it changes the password and keeps the one it replaced."
    ),
    Section(
        verdict = VaultImport.Verdict.PREVIOUS,
        title = "Older than the copy here",
        body = "The app they came from is behind. The password here is left exactly as it is, and " +
            "the older one is filed as a previous password — which is where it is worth having, " +
            "because the account you get locked out of is the one whose password changed on one " +
            "device and not the other."
    ),
    Section(
        verdict = VaultImport.Verdict.UNDECIDED,
        title = "Two passwords, and nothing to tell them apart",
        body = "Neither side says when it was last changed, so which one is current is a question " +
            "only you can answer. Unticked: taking one replaces what is here, and the export may " +
            "be the older copy. The replaced password is kept either way."
    ),
    Section(
        verdict = VaultImport.Verdict.ALREADY_HERE,
        title = "Already in the vault",
        body = "The same account with the same password, or one already recorded here as a " +
            "previous password. Nothing to do.",
        selectable = false
    )
)

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
                val detail = listOfNotNull(dates(entry), extras(entry.candidate))
                if (detail.isNotEmpty()) {
                    Text(
                        detail.joinToString(" · "),
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

/**
 * The evidence, on the row it decided.
 *
 * Only where a date actually settled the question: saying "no date" on four hundred rows would be
 * noise, and the section heading has already said so once.
 */
private fun dates(entry: VaultImport.Entry): String? {
    if (entry.verdict != VaultImport.Verdict.REPLACES &&
        entry.verdict != VaultImport.Verdict.PREVIOUS
    ) {
        return null
    }
    val theirs = entry.candidate.updatedAt.takeIf { it > 0 } ?: return null
    val mine = entry.existing?.updatedAt?.takeIf { it > 0 } ?: return null
    val format = DateFormat.getDateInstance(DateFormat.MEDIUM)
    return "changed ${format.format(Date(theirs))}, this one ${format.format(Date(mine))}"
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
                    text = listOfNotNull(
                        "${state.added} added".takeIf { state.added > 0 },
                        "${state.updated} updated".takeIf { state.updated > 0 },
                        "${state.recorded} kept as previous passwords".takeIf { state.recorded > 0 }
                    ).ifEmpty { listOf("Nothing") }
                        .joinToString(", ")
                        .plus(" from ${state.source}."),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "If that export was saved anywhere — Downloads, a cloud folder, the " +
                        "computer it was made on — delete it now. It is every password you have, " +
                        "in plain text, and it does not get safer by being forgotten. A file sent " +
                        "straight here from a share sheet was never saved at all, which is the " +
                        "whole reason that route exists.",
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

private const val SKIPPED_SHOWN = 20
