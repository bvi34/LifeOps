package com.health.app.ui.coverage

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.health.app.data.model.CareRole
import com.health.app.data.model.CareTeamMember
import com.health.app.data.model.NetworkCheck
import com.health.app.data.model.Provider
import com.health.app.logic.NetworkStatus
import com.health.app.logic.NetworkVerdict
import com.health.app.ui.common.SectionCard
import com.health.app.ui.common.formatStamp

/**
 * The care team, and whether each of them was in network the last time anyone checked.
 */

@Composable
internal fun DoctorsList(
    members: List<CareTeamMember>,
    unlinkedProviders: List<Provider>,
    personName: String?,
    checking: Set<String>,
    canCheck: Boolean,
    onCheck: (CareTeamMember) -> Unit,
    onCheckAll: () -> Unit,
    onHistory: (CareTeamMember) -> Unit,
    onPhone: (CareTeamMember) -> Unit,
    onRole: (CareTeamMember, CareRole) -> Unit,
    onEdit: (Provider) -> Unit,
    onUnlink: (CareTeamMember) -> Unit,
    onLinkExisting: (Provider, CareRole) -> Unit,
    onAdd: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (members.isEmpty() && unlinkedProviders.isEmpty()) {
            item {
                EmptyState(
                    title = "No doctors yet",
                    body = "Add the people ${personName ?: "this person"} actually sees — the GP, the " +
                        "dentist, the specialist. They belong to the household rather than to a policy, " +
                        "so changing insurer doesn't mean typing them all in again. With their NPI, " +
                        "Health can check them against the plan's own directory.",
                    action = "Add a doctor",
                    onAction = onAdd
                )
            }
        }

        if (members.size > 1 && canCheck) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onCheckAll, enabled = checking.isEmpty()) {
                        Text("Check everybody against the directory")
                    }
                }
            }
        }

        items(members, key = { it.link.id }) { member ->
            CareTeamRow(
                member = member,
                checking = member.provider.id in checking,
                canCheck = canCheck,
                onCheck = { onCheck(member) },
                onHistory = { onHistory(member) },
                onPhone = { onPhone(member) },
                onRole = { onRole(member, it) },
                onEdit = { onEdit(member.provider) },
                onUnlink = { onUnlink(member) }
            )
        }

        if (unlinkedProviders.isNotEmpty()) {
            item {
                SectionCard(title = "Elsewhere in the household") {
                    Text(
                        "Seen by somebody else here. One tap adds them to ${personName ?: "this person"} too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    unlinkedProviders.forEach { provider ->
                        ListItem(
                            headlineContent = { Text(provider.name) },
                            supportingContent = provider.descriptor?.let {
                                { Text(it, style = MaterialTheme.typography.bodySmall) }
                            },
                            trailingContent = {
                                TextButton(onClick = { onLinkExisting(provider, CareRole.SPECIALIST) }) {
                                    Text("Add")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CareTeamRow(
    member: CareTeamMember,
    checking: Boolean,
    canCheck: Boolean,
    onCheck: () -> Unit,
    onHistory: () -> Unit,
    onPhone: () -> Unit,
    onRole: (CareRole) -> Unit,
    onEdit: () -> Unit,
    onUnlink: () -> Unit
) {
    var rolesOpen by remember { mutableStateOf(false) }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        member.provider.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    member.provider.descriptor?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                VerdictBadge(member.network.verdict)
            }

            Box {
                AssistChip(onClick = { rolesOpen = true }, label = { Text(member.link.role.label) })
                DropdownMenu(expanded = rolesOpen, onDismissRequest = { rolesOpen = false }) {
                    CareRole.entries.forEach { role ->
                        DropdownMenuItem(
                            text = { Text(role.label) },
                            onClick = {
                                onRole(role)
                                rolesOpen = false
                            }
                        )
                    }
                }
            }

            Text(member.network.summary, style = MaterialTheme.typography.bodySmall)

            if (!member.provider.npiLooksValid) {
                // Caught before a request, because a mistyped NPI and a doctor who has left the
                // network both come back as no results.
                Text(
                    "That NPI doesn't pass its own check digit — worth re-reading off the paperwork.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Checking…", style = MaterialTheme.typography.bodySmall)
                } else {
                    FilledTonalButton(onClick = onCheck, enabled = canCheck) { Text("Check") }
                    TextButton(onClick = onPhone) { Text("By phone") }
                    if (member.checks.isNotEmpty()) {
                        TextButton(onClick = onHistory) { Text("History") }
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onUnlink) { Text("Remove") }
            }
        }
    }
}

/** The verdict, coloured only where it means somebody should pick up a phone. */
@Composable
private fun VerdictBadge(verdict: NetworkVerdict) {
    val color = when (verdict) {
        NetworkVerdict.DROPPED, NetworkVerdict.DECLINED -> MaterialTheme.colorScheme.error
        NetworkVerdict.NEVER_LISTED, NetworkVerdict.AMBIGUOUS -> MaterialTheme.colorScheme.tertiary
        NetworkVerdict.IN_NETWORK, NetworkVerdict.CONFIRMED_BY_PHONE -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = MaterialTheme.shapes.small, color = color.copy(alpha = 0.12f)) {
        Text(
            verdict.label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * Every check ever made about this doctor, oldest first.
 *
 * The point of showing it is that the verdict above is *derived* from this list, and a household
 * being told "was listed, not any more" deserves to see the two rows that say so. Individual rows can
 * be deleted for the mis-taps; there is deliberately no "clear history", because clearing it means
 * making the app forget the doctor used to be in network, which is the fact worth keeping.
 */
@Composable
internal fun NetworkHistorySheet(
    member: CareTeamMember,
    onDismiss: () -> Unit,
    onDeleteCheck: (NetworkCheck) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(member.provider.name) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(member.network.summary, style = MaterialTheme.typography.bodyMedium)
                if (member.network.stale) {
                    Text(
                        "Directories are revised constantly — an answer older than " +
                            "${NetworkStatus.STALE_AFTER_DAYS} days is worth repeating.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider()
                member.checks.sortedByDescending { it.checkedAt }.forEach { check ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(check.outcome.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                formatStamp(check.checkedAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        listOfNotNull(
                            check.directoryLabel,
                            check.matchedName?.let { "matched \"$it\"" },
                            check.networks.takeIf { it.isNotEmpty() }?.joinToString(", "),
                            check.detail
                        ).forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { onDeleteCheck(check) }) { Text("Delete this record") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
