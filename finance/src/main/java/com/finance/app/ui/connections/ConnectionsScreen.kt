package com.finance.app.ui.connections

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.finance.app.data.net.PlaidClient
import com.finance.app.data.repository.FinanceRepository
import com.finance.app.data.repository.FinanceSync
import com.finance.app.data.secure.FinanceSecrets
import com.finance.app.logic.Connection
import com.finance.app.logic.Endpoints
import com.finance.app.logic.Provider
import com.finance.app.ui.common.SectionCard
import com.operations.suite.ui.fields.SuiteTextField
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/** What the linking flow is doing right now, so the screen can say so rather than just spinning. */
sealed interface LinkStage {
    data object Idle : LinkStage
    data object Starting : LinkStage

    /**
     * The hosted page is open in a browser and we are asking Plaid whether they've finished.
     *
     * [repairing] distinguishes adding a bank from re-authorising one that already exists. The
     * mechanics are nearly identical and the wording must not be: somebody repairing a connection
     * needs to know their history is not about to be replaced.
     */
    data class Waiting(val hostedUrl: String, val repairing: Boolean = false) : LinkStage
    data object Finishing : LinkStage
    data class Failed(val message: String) : LinkStage
    data class Done(val institution: String, val repaired: Boolean = false) : LinkStage
}

class ConnectionsViewModel(
    private val repository: FinanceRepository,
    private val secrets: FinanceSecrets,
    private val sync: FinanceSync
) : ViewModel() {

    val connections: StateFlow<List<Connection>> = repository.observeConnections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _stage = MutableStateFlow<LinkStage>(LinkStage.Idle)
    val stage: StateFlow<LinkStage> = _stage.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun plaidKeys(): FinanceSecrets.PlaidKeys? = secrets.plaidKeys

    fun savePlaidKeys(clientId: String, secret: String, environment: Endpoints.PlaidEnvironment) {
        secrets.plaidKeys = FinanceSecrets.PlaidKeys(clientId, secret, environment)
        _message.value = "Plaid keys saved on this device."
    }

    /**
     * Start a Hosted Link session and wait for it.
     *
     * The wait is a poll rather than a callback, and it has a **deadline** rather than running until
     * somebody navigates away: an app that polls a remote API forever because a browser tab was
     * closed is an app that quietly drains a battery. Ten minutes is longer than any bank login and
     * short enough that a forgotten flow costs nothing.
     */
    fun startPlaidLink() {
        val keys = secrets.plaidKeys ?: run {
            _stage.value = LinkStage.Failed("Add your Plaid client id and secret first.")
            return
        }
        viewModelScope.launch {
            _stage.value = LinkStage.Starting
            val client = PlaidClient(keys.clientId, keys.secret, keys.environment)
            try {
                val start = client.startLink(userId = stableUserId())
                val hosted = start.hostedUrl
                if (hosted == null || !Endpoints.permits(hosted)) {
                    // Plaid returning a URL this app would not open is not a case that should ever
                    // happen, and is exactly the case where guessing would be wrong.
                    _stage.value = LinkStage.Failed("Plaid didn't give a sign-in page we can open.")
                    return@launch
                }
                _stage.value = LinkStage.Waiting(hosted, repairing = false)

                var waited = 0L
                while (waited < LINK_TIMEOUT_MS) {
                    delay(POLL_INTERVAL_MS)
                    waited += POLL_INTERVAL_MS
                    val result = client.linkResult(start.linkToken) ?: continue

                    _stage.value = LinkStage.Finishing
                    val exchange = client.exchange(result.publicToken)
                    val connectionId = UUID.randomUUID().toString()
                    val name = result.institutionName
                        ?: result.institutionId?.let { client.institutionName(it) }
                        ?: "Bank"
                    // The token first, then the row. A row without its token is a connection that
                    // looks present and cannot refresh; a token without its row is an orphan the
                    // next link overwrites harmlessly. The name is resolved before either, so the
                    // copy filed in the vault is called "USAA access token" rather than a UUID.
                    secrets.setToken(connectionId, exchange.accessToken, label = name)
                    repository.upsertConnection(
                        Connection(
                            id = connectionId,
                            provider = Provider.PLAID,
                            displayName = name,
                            institutionId = result.institutionId,
                            itemId = exchange.itemId,
                            addedAt = System.currentTimeMillis()
                        )
                    )
                    _stage.value = LinkStage.Done(name)
                    sync.refreshAll()
                    return@launch
                }
                _stage.value = LinkStage.Failed("That sign-in didn't finish. Try again when you're ready.")
            } catch (e: Exception) {
                _stage.value = LinkStage.Failed(e.message ?: "Couldn't reach Plaid.")
            }
        }
    }

    /**
     * Re-authorise a connection the bank has locked, **in place**.
     *
     * This exists because the alternative was genuinely broken: without update mode, the only way
     * back from `ITEM_LOGIN_REQUIRED` was to add the bank again, which minted a second connection
     * with a second copy of every account — both counted in net worth, with the dead one still
     * sitting there. A re-authorisation has to repair what is already here or it is not a
     * re-authorisation.
     *
     * Completion is detected by reading the account again rather than by polling Plaid for a link
     * result. In update mode there is no public token handed back, and "can I read this account
     * now" is the question the app actually cares about — see [PlaidClient.canRead].
     */
    fun repairPlaidLink(connection: Connection) {
        val keys = secrets.plaidKeys ?: run {
            _stage.value = LinkStage.Failed("Add your Plaid client id and secret first.")
            return
        }
        val token = secrets.token(connection.id) ?: run {
            _stage.value = LinkStage.Failed(
                "That connection has no token left on this device — remove it and add the bank again."
            )
            return
        }
        viewModelScope.launch {
            _stage.value = LinkStage.Starting
            val client = PlaidClient(keys.clientId, keys.secret, keys.environment)
            try {
                val start = client.startLink(userId = stableUserId(), accessToken = token)
                val hosted = start.hostedUrl
                if (hosted == null || !Endpoints.permits(hosted)) {
                    _stage.value = LinkStage.Failed("Plaid didn't give a sign-in page we can open.")
                    return@launch
                }
                _stage.value = LinkStage.Waiting(hosted, repairing = true)

                var waited = 0L
                while (waited < LINK_TIMEOUT_MS) {
                    delay(POLL_INTERVAL_MS)
                    waited += POLL_INTERVAL_MS
                    if (!client.canRead(token)) continue

                    _stage.value = LinkStage.Finishing
                    repository.markProblem(connection.id, needsReauth = false, error = null)
                    _stage.value = LinkStage.Done(connection.displayName, repaired = true)
                    sync.refreshAll()
                    return@launch
                }
                _stage.value = LinkStage.Failed("That sign-in didn't finish. Try again when you're ready.")
            } catch (e: Exception) {
                _stage.value = LinkStage.Failed(e.message ?: "Couldn't reach Plaid.")
            }
        }
    }

    fun dismissLink() {
        _stage.value = LinkStage.Idle
    }

    /** Add a Mercury connection from a token typed in. No handshake — the token is the connection. */
    fun addMercury(nickname: String, apiToken: String) {
        viewModelScope.launch {
            _busy.value = true
            val connectionId = UUID.randomUUID().toString()
            secrets.setToken(connectionId, apiToken.trim(), label = nickname.ifBlank { "Mercury" })
            repository.upsertConnection(
                Connection(
                    id = connectionId,
                    provider = Provider.MERCURY,
                    displayName = nickname.ifBlank { "Mercury" },
                    institutionId = null,
                    itemId = null,
                    addedAt = System.currentTimeMillis()
                )
            )
            val report = sync.refreshAll()
            _busy.value = false
            _message.value = if (report.ok) "Mercury connected." else report.firstError
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _busy.value = true
            val report = sync.refreshAll()
            _busy.value = false
            _message.value = when {
                report.needReauth > 0 -> "${report.needReauth} connection needs signing in again."
                report.failed > 0 -> report.firstError ?: "Some connections didn't refresh."
                else -> "Everything is up to date."
            }
        }
    }

    /**
     * Remove a connection: the token first, then the row.
     *
     * That order matters more than it looks. Dropping the row first would leave a token behind with
     * nothing naming it — an access grant on somebody's bank account that no screen can see and no
     * code will ever clear. Dropping the token first is at worst a row that cannot refresh, which is
     * visible and recoverable.
     */
    fun disconnect(connectionId: String) {
        viewModelScope.launch {
            secrets.forget(connectionId)
            repository.deleteConnection(connectionId)
            _message.value = "Disconnected. The accounts and history are gone with it."
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    /**
     * A stable id for this install, for Plaid's `client_user_id`.
     *
     * Derived from nothing personal and never sent anywhere else. Plaid wants a per-user identifier
     * and the honest one for a single-user app is a random string generated once — not an email, not
     * a device id, and certainly not anything that could be correlated across installs.
     */
    private fun stableUserId(): String {
        val existing = secrets.token(USER_ID_SLOT)
        if (existing != null) return existing
        val minted = UUID.randomUUID().toString()
        secrets.setToken(USER_ID_SLOT, minted, label = "Plaid user id")
        return minted
    }

    class Factory(
        private val repository: FinanceRepository,
        private val secrets: FinanceSecrets,
        private val sync: FinanceSync
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ConnectionsViewModel(repository, secrets, sync) as T
    }

    private companion object {
        const val POLL_INTERVAL_MS = 3_000L
        const val LINK_TIMEOUT_MS = 10L * 60L * 1000L

        /** Not a connection — a reserved slot in the same encrypted store for the install's own id. */
        const val USER_ID_SLOT = "__install_user_id"
    }
}

/**
 * Connections: where the household hands this app the keys, and where it is told exactly what it is
 * handing over.
 *
 * The prose on this screen is not decoration. It is the only place somebody finds out that their
 * Plaid secret lives on this phone rather than on a server, that it is deliberate, and what they get
 * for it — and an app that asks for a credential of this size without saying that plainly has not
 * earned the credential.
 */
@Composable
fun ConnectionsScreen(vm: ConnectionsViewModel) {
    val connections by vm.connections.collectAsStateWithLifecycle()
    val stage by vm.stage.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showPlaidKeys by remember { mutableStateOf(false) }
    var showMercury by remember { mutableStateOf(false) }
    var confirmRemoval by remember { mutableStateOf<Connection?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { PromiseCard() }

        item {
            SectionCard(
                title = "Your banks",
                subtitle = if (connections.isEmpty()) {
                    "Nothing connected yet."
                } else {
                    "${connections.size} connected."
                },
                trailing = {
                    if (busy) {
                        CircularProgressIndicator(Modifier.height(20.dp))
                    } else {
                        TextButton(onClick = vm::refresh) { Text("Refresh") }
                    }
                }
            ) {
                connections.forEach { connection ->
                    ConnectionRow(
                        connection = connection,
                        onRepair = { vm.repairPlaidLink(connection) },
                        onRemove = { confirmRemoval = connection }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.startPlaidLink() }) { Text("Add a bank") }
                    OutlinedButton(onClick = { showMercury = true }) { Text("Add Mercury") }
                }
            }
        }

        item { PlaidKeysCard(vm, onEdit = { showPlaidKeys = true }) }
        item { MercuryHelpCard() }
    }

    if (showPlaidKeys) {
        PlaidKeysDialog(
            existing = vm.plaidKeys(),
            onSave = { id, secret, env ->
                vm.savePlaidKeys(id, secret, env)
                showPlaidKeys = false
            },
            onDismiss = { showPlaidKeys = false }
        )
    }

    if (showMercury) {
        MercuryDialog(
            onSave = { nickname, token ->
                vm.addMercury(nickname, token)
                showMercury = false
            },
            onDismiss = { showMercury = false }
        )
    }

    confirmRemoval?.let { connection ->
        AlertDialog(
            onDismissRequest = { confirmRemoval = null },
            title = { Text("Disconnect ${connection.displayName}?") },
            text = {
                Text(
                    "The access token is deleted from this phone, and so are the accounts and the " +
                        "transaction history that came through it. Bills already on your LifeOps " +
                        "week stay there until they're paid or you remove them."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.disconnect(connection.id)
                    confirmRemoval = null
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoval = null }) { Text("Keep it") } }
        )
    }

    LinkDialog(stage = stage, context = context, onDismiss = vm::dismissLink)

    message?.let { text ->
        AlertDialog(
            onDismissRequest = vm::clearMessage,
            text = { Text(text) },
            confirmButton = { TextButton(onClick = vm::clearMessage) { Text("OK") } }
        )
    }
}

@Composable
private fun PromiseCard() {
    SectionCard(title = "What this app can and can't do") {
        Text(
            "It reads, and only reads. There is no transfer, payment or bill-pay call anywhere in " +
                "this app — not switched off, absent. It asks Plaid only for transactions and " +
                "liabilities, and Mercury for a read-only token, so even the credentials it holds " +
                "can't move money.\n\n" +
                "Your keys stay on this phone, behind the Android keystore. Nothing about your " +
                "money is sent anywhere except to Plaid and Mercury themselves — no analytics, no " +
                "crash reporting, and no server belonging to whoever wrote this.\n\n" +
                "The cost of that: your backups don't include these credentials, so restoring onto " +
                "a new phone means connecting again.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun PlaidKeysCard(vm: ConnectionsViewModel, onEdit: () -> Unit) {
    val keys = vm.plaidKeys()
    SectionCard(
        title = "Plaid keys",
        subtitle = if (keys == null) {
            "Not set. Adding a bank needs these."
        } else {
            "Set, for ${keys.environment.label}."
        },
        trailing = { TextButton(onClick = onEdit) { Text(if (keys == null) "Add" else "Change") } }
    ) {
        Text(
            "Plaid is how USAA and most banks are reachable at all, and it wants a developer " +
                "account — yours, free for personal use. Create one at plaid.com, then paste the " +
                "client id and secret here.\n\n" +
                "Plaid's own advice is that a secret belongs on a server. This suite has one user " +
                "and no server, so it lives on this phone instead, encrypted. That's a deliberate " +
                "trade, and what it buys is that your balances never pass through anybody else's " +
                "machine on the way to you.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MercuryHelpCard() {
    SectionCard(title = "Mercury") {
        Text(
            "Mercury needs no Plaid account: it issues its own API tokens. In Mercury, go to " +
                "Settings → API tokens and make a token with read-only access, then paste it here.\n\n" +
                "Because it's read-only at Mercury's end rather than at ours, it's the one " +
                "connection where you don't have to take this app's word for it. Mercury doesn't " +
                "report statement due dates, though, so its bills are predicted from what has come " +
                "round before.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConnectionRow(connection: Connection, onRepair: () -> Unit, onRemove: () -> Unit) {
    val needsSignIn = connection.status() == Connection.Status.NEEDS_SIGN_IN
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(connection.displayName, fontWeight = FontWeight.SemiBold)
                    val (line, colour) = when (connection.status()) {
                        // Needing to sign in again is the routine event, so it gets the ordinary
                        // accent rather than the error colour — it is a thing to do, not a thing
                        // that broke.
                        Connection.Status.NEEDS_SIGN_IN ->
                            "Your bank wants you to sign in again" to MaterialTheme.colorScheme.primary
                        Connection.Status.PROBLEM ->
                            (connection.lastError ?: "Something went wrong") to MaterialTheme.colorScheme.error
                        Connection.Status.NEVER_REFRESHED ->
                            "Not refreshed yet" to MaterialTheme.colorScheme.onSurfaceVariant
                        Connection.Status.OK ->
                            "${connection.provider.label} · up to date" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(line, style = MaterialTheme.typography.bodySmall, color = colour)
                }
                TextButton(onClick = onRemove) { Text("Remove") }
            }

            // The action that makes the state above actionable. Without it the row said "sign in
            // again" and offered no way to, and the only route back was adding the bank a second
            // time — which left two of everything, both counted.
            if (needsSignIn && connection.provider.canExpire) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRepair, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign in again")
                }
                Text(
                    "This repairs the connection you already have. Your accounts, history and bills " +
                        "stay exactly as they are.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun LinkDialog(stage: LinkStage, context: Context, onDismiss: () -> Unit) {
    when (stage) {
        is LinkStage.Idle -> Unit

        is LinkStage.Starting -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Starting") },
            text = { Text("Asking Plaid for a sign-in page…") },
            confirmButton = {}
        )

        is LinkStage.Waiting -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (stage.repairing) "Sign in again" else "Sign in to your bank") },
            text = {
                Text(
                    buildString {
                        append("Open the page, sign in, and come back — this will notice when you're done.\n\n")
                        if (stage.repairing) {
                            append(
                                "This repairs the connection you already have: nothing is added and " +
                                    "nothing is replaced, so your accounts, history and bills are " +
                                    "untouched.\n\n"
                            )
                        }
                        append(
                            "The page is Plaid's, not this app's: your bank password is typed there " +
                                "and never passes through here."
                        )
                    }
                )
            },
            confirmButton = {
                Button(onClick = { openPage(context, stage.hostedUrl) }) { Text("Open the page") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )

        is LinkStage.Finishing -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Almost there") },
            text = { Text("Finishing the connection…") },
            confirmButton = {}
        )

        is LinkStage.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    if (stage.repaired) "${stage.institution} is back" else "${stage.institution} connected"
                )
            },
            text = {
                Text(
                    if (stage.repaired) {
                        "Catching up on anything that happened while it was locked."
                    } else {
                        "Pulling the accounts and history now. It may take a minute."
                    }
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Good") } }
        )

        is LinkStage.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("That didn't work") },
            text = { Text(stage.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
        )
    }
}

@Composable
private fun PlaidKeysDialog(
    existing: FinanceSecrets.PlaidKeys?,
    onSave: (String, String, Endpoints.PlaidEnvironment) -> Unit,
    onDismiss: () -> Unit
) {
    var clientId by remember { mutableStateOf(existing?.clientId.orEmpty()) }
    var secret by remember { mutableStateOf("") }
    var environment by remember {
        mutableStateOf(existing?.environment ?: Endpoints.PlaidEnvironment.SANDBOX)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Plaid keys") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SuiteTextField(label = "Client id", value = clientId, onValueChange = { clientId = it })
                SuiteTextField(
                    label = "Secret",
                    value = secret,
                    onValueChange = { secret = it },
                    supporting = if (existing == null) null else "Leave blank to keep the one you saved."
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Endpoints.PlaidEnvironment.entries.forEach { option ->
                        FilterChip(
                            selected = environment == option,
                            onClick = { environment = option },
                            label = { Text(option.label) }
                        )
                    }
                }
                Text(
                    "Sandbox uses Plaid's fake test bank — worth trying first, because a wrong key " +
                        "against production just fails confusingly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = clientId.isNotBlank() && (secret.isNotBlank() || existing != null),
                onClick = {
                    // Blank means "keep what's there", which is what lets somebody change only the
                    // environment without re-typing a fifty-character secret.
                    onSave(clientId, secret.ifBlank { existing?.secret.orEmpty() }, environment)
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun MercuryDialog(onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var nickname by remember { mutableStateOf("Mercury") }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect Mercury") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SuiteTextField(label = "Call it", value = nickname, onValueChange = { nickname = it })
                SuiteTextField(
                    label = "API token",
                    value = token,
                    onValueChange = { token = it },
                    supporting = "Mercury → Settings → API tokens. Make it read-only."
                )
            }
        },
        confirmButton = {
            TextButton(enabled = token.isNotBlank(), onClick = { onSave(nickname, token) }) {
                Text("Connect")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Hand the hosted page to a browser.
 *
 * A browser rather than a WebView inside this app, and that is a security decision rather than a
 * convenience one: a WebView is a window this app could read the contents of, and a bank password
 * should be typed somewhere this app demonstrably cannot see. The browser also shows the address
 * bar and the padlock, which is how somebody checks they are on Plaid's page and not a copy of it.
 */
private fun openPage(context: Context, url: String) {
    if (!Endpoints.permits(url)) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
