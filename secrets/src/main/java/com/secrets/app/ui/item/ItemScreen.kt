package com.secrets.app.ui.item

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.operations.suite.ui.fields.SuiteNoteField
import com.operations.suite.ui.fields.SuiteTextField
import com.operations.vaultkit.PasswordGenerator
import com.operations.vaultkit.PasswordRecipe
import com.operations.vaultkit.Totp
import com.operations.vaultkit.TotpConfig
import com.operations.vaultkit.VaultField
import com.operations.vaultkit.VaultItem
import com.operations.vaultkit.VaultItemKind
import com.operations.vaultkit.VaultSecretVersion
import com.secrets.app.data.SecretsPrefs
import com.secrets.app.data.VaultStore
import com.secrets.app.ui.common.SecretClipboard
import com.secrets.app.ui.common.SecretValue
import com.secrets.app.ui.common.StrengthBar
import com.secrets.app.ui.common.ownerLabel
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One item, read and written on the same screen.
 *
 * There is no separate "view" mode. A vault whose items are read-only until you press a pencil is a
 * vault where changing a password is four taps, and the read-only mode was protecting nothing —
 * anybody who has the screen open already has the passwords. What *is* protected is the value
 * itself, which stays masked until it is revealed, one field at a time (see
 * [com.secrets.app.ui.common.SecretValue]).
 *
 * Changes are saved when Save is pressed, not as they are typed. A save re-seals and rewrites the
 * whole file, so saving per keystroke would be wasteful — but the real reason is that a
 * half-typed password written to the vault is a password that no longer opens anything, and an
 * editor that autosaves gives nobody the chance to change their mind. Leaving without saving keeps
 * what was there before, which is the behaviour a text field in a vault should have.
 */
class ItemViewModel(
    private val store: VaultStore,
    private val itemId: String?
) : ViewModel() {

    data class State(
        val item: VaultItem,
        val isNew: Boolean,
        val dirty: Boolean = false,
        val gone: Boolean = false
    )

    private val _state = MutableStateFlow(
        State(
            item = store.document.value?.item(itemId.orEmpty()) ?: blank(),
            isNew = itemId == null || store.document.value?.item(itemId) == null
        )
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private fun blank(): VaultItem {
        val now = System.currentTimeMillis()
        return VaultItem(id = UUID.randomUUID().toString(), createdAt = now, updatedAt = now)
    }

    private fun edit(block: (VaultItem) -> VaultItem) {
        _state.value = _state.value.copy(item = block(_state.value.item), dirty = true)
    }

    fun setTitle(value: String) = edit { it.copy(title = value) }
    fun setUsername(value: String) = edit { it.copy(username = value) }
    fun setSecret(value: String) = edit { it.copy(secret = value) }
    fun setUrl(value: String) = edit { it.copy(url = value) }
    fun setNote(value: String) = edit { it.copy(note = value) }
    fun setKind(kind: VaultItemKind) = edit { it.copy(kind = kind) }
    fun setFavourite(value: Boolean) = edit { it.copy(favourite = value) }

    fun setTags(raw: String) = edit { item ->
        item.copy(tags = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() })
    }

    fun addField() = edit { it.copy(fields = it.fields + VaultField(name = "", value = "")) }

    fun setField(index: Int, field: VaultField) = edit { item ->
        item.copy(fields = item.fields.mapIndexed { i, existing -> if (i == index) field else existing })
    }

    fun removeField(index: Int) = edit { item ->
        item.copy(fields = item.fields.filterIndexed { i, _ -> i != index })
    }

    fun generate() = edit { it.copy(secret = PasswordGenerator.password(PasswordRecipe.DEFAULT).value) }

    /**
     * Take a second-factor seed, in either shape a site hands one over.
     *
     * Returns false for anything that will not produce codes, so the screen can say so while it is
     * still fixable. Storing an unusable seed would mean an item that shows nothing, or worse shows
     * six confident and wrong digits, and finding that out at a sign-in page is finding it out at
     * the worst possible moment.
     */
    fun setTotp(raw: String): Boolean {
        val parsed = Totp.parse(raw) ?: return false
        edit { it.copy(totp = parsed) }
        return true
    }

    fun clearTotp() = edit { it.copy(totp = null) }

    /**
     * Throw away the passwords this item used to have.
     *
     * Like every other edit on this screen it takes effect on Save, which is the behaviour somebody
     * pressing a button labelled "forget" in a vault should get: a chance to leave without it having
     * happened.
     */
    fun clearHistory() = edit { it.copy(history = emptyList()) }

    /**
     * Write the item back, if anything changed and there is anything to write.
     *
     * An untouched new item is discarded rather than saved: opening "add", thinking better of it and
     * pressing back should not leave an untitled row in the vault.
     */
    fun save(onDone: () -> Unit = {}) {
        val current = _state.value
        if (!current.dirty) {
            onDone()
            return
        }
        val item = current.item
        // A second factor on its own is a real item — a seed with no password beside it is exactly
        // what somebody adds when the password lives in their head and the codes do not.
        if (item.title.isBlank() && item.secret.isBlank() && item.username.isBlank() && !item.hasTotp) {
            onDone()
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            store.mutate { it.upsert(item.copy(updatedAt = now), now) }
            _state.value = current.copy(dirty = false)
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            store.mutate { it.delete(_state.value.item.id, System.currentTimeMillis()) }
            _state.value = _state.value.copy(gone = true, dirty = false)
            onDone()
        }
    }

    class Factory(
        private val store: VaultStore,
        private val itemId: String?
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ItemViewModel(store, itemId) as T
    }
}

@Composable
fun ItemScreen(
    vm: ItemViewModel,
    prefs: SecretsPrefs,
    onDone: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val item = state.item
    var confirmDelete by remember { mutableStateOf(false) }
    var kindMenu by remember { mutableStateOf(false) }

    fun copy(label: String, value: String) {
        SecretClipboard.copy(context, label, value, prefs.clipboardClearSeconds)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (item.isManaged) {
            ManagedBanner(item)
        }

        SuiteTextField(
            label = "Name",
            value = item.title,
            onValueChange = vm::setTitle,
            placeholder = "What is this for?"
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { kindMenu = true }) { Text(item.kind.label) }
            DropdownMenu(expanded = kindMenu, onDismissRequest = { kindMenu = false }) {
                VaultItemKind.entries.forEach { kind ->
                    DropdownMenuItem(
                        text = { Text(kind.label) },
                        onClick = {
                            vm.setKind(kind)
                            kindMenu = false
                        }
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text("Favourite", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = item.favourite, onCheckedChange = vm::setFavourite)
        }

        if (item.kind != VaultItemKind.NOTE) {
            SuiteTextField(
                label = "Username",
                value = item.username,
                onValueChange = vm::setUsername,
                capitalise = KeyboardCapitalization.None,
                trailing = {
                    if (item.username.isNotBlank()) {
                        IconButton(onClick = { copy("Username", item.username) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy username")
                        }
                    }
                }
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecretValue(
                    value = item.secret,
                    label = item.kind.secretLabel,
                    onCopy = { copy(item.kind.secretLabel, item.secret) }
                )
                SuiteTextField(
                    label = "Set ${item.kind.secretLabel.lowercase()}",
                    value = item.secret,
                    onValueChange = vm::setSecret,
                    capitalise = KeyboardCapitalization.None,
                    trailing = {
                        IconButton(onClick = vm::generate) {
                            Icon(Icons.Filled.Casino, contentDescription = "Generate")
                        }
                    }
                )
                if (item.secret.isNotEmpty()) {
                    StrengthBar(secret = item.secret)
                }
            }
        }

        // Offered for anything a person owns. A mirrored credential is read back by the app that
        // filed it and by nothing else, so a second factor on one would be a seed nobody ever asks
        // for — though one that somehow exists is still shown rather than hidden.
        if (!item.isManaged || item.hasTotp) {
            TotpSection(
                config = item.totp,
                clipboardClearSeconds = prefs.clipboardClearSeconds,
                onSet = vm::setTotp,
                onClear = vm::clearTotp
            )
        }

        if (item.kind == VaultItemKind.LOGIN) {
            SuiteTextField(
                label = "Address",
                value = item.url,
                onValueChange = vm::setUrl,
                capitalise = KeyboardCapitalization.None,
                placeholder = "https://"
            )
        }

        item.fields.forEachIndexed { index, field ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SuiteTextField(
                            label = "Field",
                            value = field.name,
                            onValueChange = { vm.setField(index, field.copy(name = it)) },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { vm.removeField(index) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove field")
                        }
                    }
                    SuiteTextField(
                        label = "Value",
                        value = field.value,
                        onValueChange = { vm.setField(index, field.copy(value = it)) },
                        capitalise = KeyboardCapitalization.None
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = field.secret,
                            onClick = { vm.setField(index, field.copy(secret = !field.secret)) },
                            label = { Text(if (field.secret) "Hidden" else "Shown") }
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { copy(field.name.ifBlank { "Field" }, field.value) }) {
                            Text("Copy")
                        }
                    }
                }
            }
        }

        OutlinedButton(onClick = vm::addField) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add a field")
        }

        SuiteTextField(
            label = "Tags",
            value = item.tags.joinToString(", "),
            onValueChange = vm::setTags,
            supporting = "Comma separated. Tags are how this vault is organised; there are no folders."
        )

        SuiteNoteField(
            label = "Note",
            value = item.note,
            onValueChange = vm::setNote
        )

        if (item.history.isNotEmpty()) {
            HistoryCard(
                history = item.history,
                secretLabel = item.kind.secretLabel,
                onCopy = { value -> copy("Previous ${item.kind.secretLabel.lowercase()}", value) },
                onForget = vm::clearHistory
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.save(onDone) }) { Text(if (state.isNew) "Add" else "Save") }
            if (!state.isNew) {
                OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this item?") },
            text = {
                Text(
                    if (item.isManaged) {
                        "This credential belongs to ${ownerLabel(item) ?: "another app"}. Deleting it " +
                            "here does not disconnect anything — but it does mean this credential will " +
                            "not come back after a restore."
                    } else {
                        "This cannot be undone. The item is removed from the vault and from every " +
                            "backup taken after now."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(onDone)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
            }
        )
    }
}

/**
 * The banner over a credential another app filed here.
 *
 * It says who owns it and what editing it means, because the answer is not obvious and is not
 * "nothing": the owning app reads this value back through the broker, so changing it here changes
 * what Finance sends to a bank. That is occasionally exactly what somebody wants — pasting in a
 * freshly rotated API token without walking through the app's own set-up screen — and it is
 * otherwise a good way to break a connection, so it is said plainly rather than left to be
 * discovered.
 */
@Composable
private fun ManagedBanner(item: VaultItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Filed here by ${ownerLabel(item) ?: "another app"}",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "That app reads this value back from the vault, so what you type here is what " +
                    "it will use. It is also why this credential survives a restore.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            item.ref?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/**
 * The second factor: the seed if there is one, the offer to add one if there is not.
 *
 * ## Why a password manager holds these at all
 *
 * The usual objection is that keeping both factors in one place collapses two factors into one, and
 * it is a real objection — but it is an argument about *where the vault is*, not about what is in
 * it. This vault is on a phone, behind a passphrase that is not on the phone, in an app that cannot
 * reach the network. The thing two-factor authentication defends against is somebody who has the
 * password and is not here: a leaked database, a reused password, a phishing page. None of those
 * gets them this file, and somebody who *does* have this file and its passphrase has the password
 * anyway.
 *
 * What the household actually had before this existed was a separate authenticator app whose seeds
 * live in its own store, die with the phone, and are the one credential that cannot be reissued
 * without a support line. That is the comparison this replaces, and it is not close.
 */
@Composable
private fun TotpSection(
    config: TotpConfig?,
    clipboardClearSeconds: Int,
    onSet: (String) -> Boolean,
    onClear: () -> Unit
) {
    if (config == null) {
        AddTotpCard(onSet = onSet)
    } else {
        TotpCard(config = config, clipboardClearSeconds = clipboardClearSeconds, onClear = onClear)
    }
}

/**
 * Six digits and the seconds they have left.
 *
 * The code is **not masked**, unlike every other secret on this screen, and that is the right way
 * round rather than an oversight: it exists to be read off the screen and typed into something else
 * within half a minute, it is worthless the moment it expires, and a reveal button in front of it
 * would be a tap between somebody and the only thing they came here for. What stays masked is the
 * seed, which is never shown at all — there is no button here that puts it on screen, because the
 * only reason to look at it is to move it to another authenticator, and that is what the QR code
 * the site still has is for.
 */
@Composable
private fun TotpCard(
    config: TotpConfig,
    clipboardClearSeconds: Int,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    // Twice a second rather than once: at one tick per second the countdown visibly skips a number
    // whenever the tick and the clock's second drift apart, which they do within a minute.
    LaunchedEffect(config) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }

    val code = Totp.code(config, now)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Shield, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Second factor", style = MaterialTheme.typography.titleSmall)
                    val subtitle = listOfNotNull(
                        config.label.takeIf { it.isNotBlank() },
                        config.unusualSettings
                    ).joinToString(" · ")
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove the second factor")
                }
            }

            if (code == null) {
                // A seed that will not decode: hand-edited, or merged out of an odd archive. Saying
                // so is the only useful thing left — silently showing nothing would look like a bug
                // in the app rather than a problem with the seed.
                Text(
                    text = "This seed will not produce codes. Remove it and add the key again from " +
                        "the site's own two-factor page.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = code.grouped,
                        // Monospace and large: this is a number somebody reads off a screen while
                        // looking at a different device, which is the worst possible reading
                        // condition and the one every other choice on this row is made for.
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${code.secondsRemaining}s",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(onClick = {
                        // An expired code is neither a secret nor any use, so it leaves the
                        // clipboard when it stops working rather than on the vault's general
                        // timer — unless the household asked for the clipboard never to be cleared,
                        // which is a setting rather than an oversight.
                        val clearIn = if (clipboardClearSeconds == 0) 0 else code.secondsRemaining
                        SecretClipboard.copy(context, "Code", code.digits, clearIn)
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy the code")
                    }
                }
                LinearProgressIndicator(
                    progress = { code.fractionRemaining },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "From this phone's clock. If codes are refused, check the time is set " +
                        "automatically — nothing here can ask a time server.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/**
 * The offer to add one, folded away until it is wanted.
 *
 * Collapsed because most items have no second factor and never will, and a permanently open text
 * field asking for a key is a field on every screen that most people have to read past to reach the
 * address box.
 *
 * It takes typed text rather than a photograph of a QR code, and it is worth being plain about why:
 * a scanner needs the camera, the camera needs a permission, and this module's manifest declares
 * none at all — that absence is the one promise it makes that nothing else in the suite makes. Every
 * site that shows a QR code also offers the same seed as text, usually behind a "can't scan it?"
 * link, and that text is what goes here.
 */
@Composable
private fun AddTotpCard(onSet: (String) -> Boolean) {
    var open by remember { mutableStateOf(false) }
    var raw by remember { mutableStateOf("") }
    var rejected by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { open = !open },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Shield, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Add a second factor",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Hide" else "Show"
                )
            }

            if (open) {
                SuiteTextField(
                    label = "Setup key",
                    value = raw,
                    onValueChange = {
                        raw = it
                        rejected = false
                    },
                    capitalise = KeyboardCapitalization.None,
                    isError = rejected,
                    supporting = if (rejected) {
                        "That is not a setup key. Look for “can't scan the code?” on the site's " +
                            "two-factor page — it shows the same key as text."
                    } else {
                        "The key the site shows beside its QR code, or the whole otpauth:// link."
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            if (onSet(raw)) {
                                raw = ""
                                open = false
                                rejected = false
                            } else {
                                rejected = true
                            }
                        },
                        enabled = raw.isNotBlank()
                    ) { Text("Add") }
                    TextButton(onClick = {
                        open = false
                        raw = ""
                        rejected = false
                    }) { Text("Cancel") }
                }
            }
        }
    }
}

/**
 * The passwords this item used to have.
 *
 * ## Why this is in a password manager and not an oversight in one
 *
 * The most common way to lose an account is not forgetting a password, it is *changing* one. The
 * form said it saved and stored something else; the change never committed; the tablet in the
 * kitchen is still signed in on the old one and will ask for it the next time somebody opens it.
 * Each of those is ten seconds' work for a person who can see what the password was this morning,
 * and an account-recovery phone call for a person who cannot.
 *
 * ## And why it is folded shut
 *
 * Because it is reference material, not the item. Somebody opening a login wants the password that
 * works; the ones that no longer do are worth keeping and are not worth being in the way. Each is
 * masked like any other secret and revealed one at a time, so a screenshot of this card open is not
 * a screenshot of five passwords.
 */
@Composable
private fun HistoryCard(
    history: List<VaultSecretVersion>,
    secretLabel: String,
    onCopy: (String) -> Unit,
    onForget: () -> Unit
) {
    var open by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { open = !open },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.History, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (history.size == 1) {
                            "One previous ${secretLabel.lowercase()}"
                        } else {
                            "${history.size} previous ${secretLabel.lowercase()}s"
                        },
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Kept here so a change that did not take can be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Hide" else "Show"
                )
            }

            if (open) {
                history.forEach { version ->
                    SecretValue(
                        value = version.secret,
                        label = "Replaced ${DateFormat.getDateInstance().format(Date(version.replacedAt))}",
                        onCopy = { onCopy(version.secret) }
                    )
                }
                TextButton(onClick = onForget) { Text("Forget these") }
                Text(
                    text = "Forgetting takes effect when you save.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
