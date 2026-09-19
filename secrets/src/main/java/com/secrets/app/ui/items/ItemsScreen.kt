package com.secrets.app.ui.items

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.operations.suite.ui.fields.SuiteTextField
import com.operations.vaultkit.VaultItem
import com.operations.vaultkit.VaultItemKind
import com.operations.vaultkit.VaultSearch
import com.secrets.app.data.SecretsPrefs
import com.secrets.app.data.VaultStore
import com.secrets.app.ui.common.ownerLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything in the vault, in one list.
 *
 * No folders. A household vault has tens of items, not thousands, and a folder tree is a filing
 * system somebody has to maintain in order for search to work worse than it would have anyway. What
 * is here instead: a search box that never looks at a secret ([VaultSearch]), and one filter that
 * answers the only structural question this vault actually has — *did I put this here, or did one of
 * the apps?*
 */
class ItemsViewModel(
    private val store: VaultStore,
    private val prefs: SecretsPrefs
) : ViewModel() {

    /** Which slice of the vault is on screen. */
    enum class Filter(val label: String) { ALL("All"), MINE("Mine"), MANAGED("From apps") }

    data class State(
        val query: String = "",
        val filter: Filter = Filter.ALL,
        val items: List<VaultItem> = emptyList(),
        val total: Int = 0,
        val managedCount: Int = 0
    )

    private val _state = MutableStateFlow(State(filter = if (prefs.showManaged) Filter.ALL else Filter.MINE))
    val state: StateFlow<State> = _state.asStateFlow()

    /** Recompute from the document. Called on every document change and on every keystroke. */
    fun refresh() {
        val document = store.document.value
        val live = document?.live.orEmpty()
        val filtered = when (_state.value.filter) {
            Filter.ALL -> live
            Filter.MINE -> live.filterNot { it.isManaged }
            Filter.MANAGED -> live.filter { it.isManaged }
        }
        _state.value = _state.value.copy(
            items = VaultSearch.search(filtered, _state.value.query),
            total = live.size,
            managedCount = live.count { it.isManaged }
        )
    }

    fun onQuery(query: String) {
        _state.value = _state.value.copy(query = query)
        refresh()
    }

    fun onFilter(filter: Filter) {
        _state.value = _state.value.copy(filter = filter)
        prefs.showManaged = filter != Filter.MINE
        refresh()
    }

    class Factory(
        private val store: VaultStore,
        private val prefs: SecretsPrefs
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ItemsViewModel(store, prefs) as T
    }
}

@Composable
fun ItemsScreen(vm: ItemsViewModel, store: VaultStore, onOpen: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val document by store.document.collectAsStateWithLifecycle()

    // The list is derived from the document, so it has to be recomputed when the document changes —
    // which happens on every save, including saves made by *another app* mirroring a credential
    // while this screen is open.
    androidx.compose.runtime.LaunchedEffect(document) { vm.refresh() }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        SuiteTextField(
            label = "Search",
            value = state.query,
            onValueChange = vm::onQuery,
            capitalise = KeyboardCapitalization.None,
            supporting = "Titles, usernames, addresses and notes — never the secrets themselves.",
            leading = { Icon(Icons.Filled.Search, contentDescription = null) }
        )

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ItemsViewModel.Filter.entries.forEach { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { vm.onFilter(filter) },
                    label = { Text(filter.label) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (state.items.isEmpty()) {
            EmptyList(query = state.query, total = state.total)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.items, key = { it.id }) { item ->
                    ItemRow(item = item, onOpen = { onOpen(item.id) })
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun ItemRow(item: VaultItem, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleSmall)
                val subtitle = subtitleFor(item)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (item.favourite) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "Favourite",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * The one line under a title.
 *
 * For something you typed in, the username — which is what you were looking for when you opened the
 * list. For something an app mirrored, *which app*, because "Finance" answers the question a person
 * scrolling past a row called "USAA access token" is actually asking: do I own this, or does the
 * software?
 */
private fun subtitleFor(item: VaultItem): String? = when {
    item.isManaged -> ownerLabel(item)?.let { "$it · ${item.kind.label}" } ?: item.kind.label
    // A passkey is worth naming in the list rather than leaving to be discovered on the way in:
    // it is the row where "what is my password here" has the answer "you do not have one".
    item.hasPasskey -> listOf("Passkey", item.username).filter { it.isNotBlank() }.joinToString(" · ")
    item.username.isNotBlank() -> item.username
    item.kind != VaultItemKind.LOGIN -> item.kind.label
    else -> null
}

@Composable
private fun EmptyList(query: String, total: Int) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = when {
                query.isNotBlank() -> "Nothing matches “$query”."
                total == 0 -> "The vault is empty."
                else -> "Nothing here under this filter."
            },
            style = MaterialTheme.typography.bodyLarge
        )
        if (query.isBlank() && total == 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Add a login with the button below — or connect something in Finance or " +
                    "Citation, and the credential will be filed here on its own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
