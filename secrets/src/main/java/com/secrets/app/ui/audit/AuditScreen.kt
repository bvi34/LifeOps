package com.secrets.app.ui.audit

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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.operations.vaultkit.VaultAudit
import com.secrets.app.data.VaultStore

/**
 * The honest mirror.
 *
 * LifeOps keeps receipts on how a week actually went and refuses to flatter anybody about it; this
 * is the same instinct pointed at a vault. It says three things and declines to say a fourth:
 *
 *  - a password is **weak**;
 *  - a password is **used more than once**, which is the finding no strength meter can produce
 *    because it is a property of the collection rather than of any one password;
 *  - a password has **not changed in two years**, as a fact rather than an instruction — rotation
 *    for its own sake stopped being advice a decade ago, and what makes age worth showing is that it
 *    usually turns up next to "used on three sites".
 *
 * The fourth thing — "this password appeared in a breach" — is not here and will not be, because
 * every implementation of it involves telling somebody else's server something about your passwords.
 * The manifest promises this module cannot reach the network, and that promise is worth more than
 * the feature.
 */
@Composable
fun AuditScreen(store: VaultStore, onOpen: (String) -> Unit) {
    val document by store.document.collectAsStateWithLifecycle()
    val report = remember(document) {
        VaultAudit.run(document?.items.orEmpty(), System.currentTimeMillis())
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = if (report.clean) "Nothing to answer for" else "${report.findings.size} things to look at",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (report.clean) {
                        "${report.examined} items, none weak, none used twice, none forgotten about."
                    } else {
                        val parts = buildList {
                            report.count(VaultAudit.Kind.WEAK).takeIf { it > 0 }?.let { add("$it weak") }
                            report.count(VaultAudit.Kind.REUSED).takeIf { it > 0 }?.let { add("$it reused") }
                            report.count(VaultAudit.Kind.STALE).takeIf { it > 0 }?.let { add("$it old") }
                            report.count(VaultAudit.Kind.NO_SECRET).takeIf { it > 0 }?.let { add("$it empty") }
                        }
                        "Across ${report.examined} items: ${parts.joinToString(", ")}."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(report.findings, key = { "${it.itemId}:${it.kind}" }) { finding ->
                Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(finding.itemId) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = finding.title.ifBlank { "Untitled" },
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = explain(finding),
                                style = MaterialTheme.typography.bodySmall,
                                color = when (finding.kind) {
                                    VaultAudit.Kind.WEAK, VaultAudit.Kind.REUSED ->
                                        MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

/** One sentence per finding, saying what is wrong in the words somebody would use. */
private fun explain(finding: VaultAudit.Finding): String = when (finding.kind) {
    VaultAudit.Kind.WEAK -> "Weak — about ${finding.bits.toInt()} bits, which is guessable."
    VaultAudit.Kind.REUSED -> "Also used for ${finding.alsoUsedBy.joinToString(", ")}."
    VaultAudit.Kind.STALE -> "Unchanged for over two years."
    VaultAudit.Kind.NO_SECRET -> "There is no password saved on this one."
}
