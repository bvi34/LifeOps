package com.secrets.app.ui.item

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import com.operations.vaultkit.VaultField
import com.operations.vaultkit.VaultItem
import com.operations.vaultkit.VaultItemKind
import com.secrets.app.data.SecretsPrefs
import com.secrets.app.data.VaultStore
import com.secrets.app.ui.common.SecretClipboard
import com.secrets.app.ui.common.SecretValue
import com.secrets.app.ui.common.StrengthBar
import com.secrets.app.ui.common.ownerLabel
import java.util.UUID
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
        if (item.title.isBlank() && item.secret.isBlank() && item.username.isBlank()) {
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
