package com.health.app.ui.coverage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.health.app.data.model.CareRole
import com.health.app.data.model.CareTeamMember
import com.health.app.data.model.CoverageCard
import com.health.app.data.model.InsurancePlan
import com.health.app.data.model.NetworkCheck
import com.health.app.data.model.Provider
import com.health.app.data.store.CardImageStore
import com.health.app.logic.CoverageKind
import com.health.app.logic.CoverageStatus
import com.health.app.logic.DirectoryOutcome
import com.health.app.logic.Insurance
import com.health.app.logic.NetworkStatus
import com.health.app.logic.NetworkVerdict
import com.health.app.logic.PlanType
import com.health.app.ui.common.ConfirmDeleteDialog
import com.health.app.ui.common.NoProfiles
import com.health.app.ui.common.ProfileBar
import com.health.app.ui.common.SectionCard
import com.health.app.ui.common.formatStamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Care tab: **who pays for this, and who do we take her to**.
 *
 * Two halves, and they are deliberately not the same thing — which is the design point of the whole
 * feature:
 *
 *  - **Cards** — the household's insurance, as copied off the card. Each person's own member number
 *    on the household's policy, whether the coverage is current, the photographs of the card, and a
 *    PDF of it on demand. Health stores what is *printed on the card*; it does not store, compute or
 *    guess what the policy covers.
 *  - **Doctors** — the care team, which belongs to the household and **not** to the insurance. The
 *    policy changes every January; the paediatrician doesn't. Each is shown with where they stand
 *    against this person's coverage, read out of the whole history of checks rather than a single
 *    flag: in the current directory, in an older one but not this one, never listed, or nobody has
 *    asked yet.
 *
 * The distinction between "the directory lists them" and "they are covered for you" is kept
 * everywhere on this screen. A payer's directory covers every network it sells, a listing is only as
 * current as the payer keeps it, and the only sentence Health will write is the one it can support.
 */
@Composable
fun CoverageScreen(vm: CoverageViewModel, onOpenPeople: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val plans by vm.plans.collectAsStateWithLifecycle()
    val coverage by vm.coverage.collectAsStateWithLifecycle()
    val careTeam by vm.careTeam.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val directory by vm.directoryState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var tab by remember { mutableIntStateOf(0) }
    var showAddPlan by remember { mutableStateOf(false) }
    var showAddDoctor by remember { mutableStateOf(false) }
    var editingPlan by remember { mutableStateOf<InsurancePlan?>(null) }
    var editingProvider by remember { mutableStateOf<Provider?>(null) }
    var viewingCard by remember { mutableStateOf<CoverageCard?>(null) }
    var joiningPlan by remember { mutableStateOf<InsurancePlan?>(null) }
    var historyFor by remember { mutableStateOf<CareTeamMember?>(null) }
    var phoneFor by remember { mutableStateOf<CareTeamMember?>(null) }
    var checkingFor by remember { mutableStateOf<CareTeamMember?>(null) }
    var removingMembership by remember { mutableStateOf<CoverageCard?>(null) }
    var deletingPlan by remember { mutableStateOf<InsurancePlan?>(null) }
    var deletingProvider by remember { mutableStateOf<Provider?>(null) }
    var deletingCheck by remember { mutableStateOf<NetworkCheck?>(null) }

    if (profiles.isEmpty()) {
        NoProfiles(onOpenPeople)
        return
    }

    /** The policies a check can sensibly run against: still held, and with an address to ask. */
    val checkablePlans = plans.filter { !it.archived }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (tab == 0) showAddPlan = true else showAddDoctor = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(if (tab == 0) "Card" else "Doctor") }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ProfileBar(profiles, selected?.id, vm::select, onOpenPeople)
            HorizontalDivider()

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Cards") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Doctors") })
            }

            directory.message?.let { message ->
                DirectoryMessage(message, onDismiss = vm::dismissMessage)
            }

            when (tab) {
                0 -> CardsList(
                    cards = coverage,
                    plans = plans,
                    personName = selected?.name,
                    images = vm.images,
                    probingPlanId = directory.probingPlanId,
                    onView = { viewingCard = it },
                    onExport = { vm.exportCard(context, it) },
                    onEditPlan = { editingPlan = it },
                    onJoinPlan = { joiningPlan = it },
                    onRemove = { removingMembership = it },
                    onProbe = vm::probeDirectory,
                    onAdd = { showAddPlan = true }
                )
                else -> DoctorsList(
                    members = careTeam,
                    unlinkedProviders = providers.filter { provider ->
                        careTeam.none { it.provider.id == provider.id }
                    },
                    personName = selected?.name,
                    checking = directory.checkingProviderIds,
                    canCheck = checkablePlans.any { it.hasDirectory },
                    onCheck = { member ->
                        val plan = checkablePlans.firstOrNull { it.hasDirectory }
                        if (plan == null || checkablePlans.count { it.hasDirectory } > 1) {
                            checkingFor = member
                        } else {
                            vm.checkNetwork(member, plan)
                        }
                    },
                    onCheckAll = {
                        checkablePlans.firstOrNull { it.hasDirectory }
                            ?.let { vm.checkAll(careTeam, it) }
                    },
                    onHistory = { historyFor = it },
                    onPhone = { phoneFor = it },
                    onRole = { member, role -> vm.setRole(member.link, role) },
                    onEdit = { editingProvider = it },
                    onUnlink = { vm.unlink(it.link.id) },
                    onLinkExisting = { provider, role -> vm.linkExistingProvider(provider.id, role) },
                    onAdd = { showAddDoctor = true }
                )
            }
        }
    }

    if (showAddPlan) {
        PlanDialog(
            personName = selected?.name,
            onDismiss = { showAddPlan = false },
            onConfirm = { plan, membership ->
                vm.addPlan(plan, membership)
                showAddPlan = false
            }
        )
    }
    editingPlan?.let { plan ->
        PlanDialog(
            existing = plan,
            personName = selected?.name,
            onDismiss = { editingPlan = null },
            onSave = {
                vm.updatePlan(it)
                editingPlan = null
            },
            onArchive = {
                vm.setPlanArchived(plan.id, !plan.archived)
                editingPlan = null
            },
            onDelete = { deletingPlan = plan }
        )
    }
    joiningPlan?.let { plan ->
        MembershipDialog(
            plan = plan,
            personName = selected?.name.orEmpty(),
            onDismiss = { joiningPlan = null },
            onConfirm = { draft ->
                vm.addMembership(plan.id, draft)
                joiningPlan = null
            }
        )
    }
    viewingCard?.let { card ->
        CardSheet(
            card = card,
            images = vm.images,
            onDismiss = { viewingCard = null },
            onExport = { vm.exportCard(context, it) },
            onAttach = { uri, front, toPlan ->
                vm.attachCardImage(card.plan.id, card.membership.id, uri, front, toPlan)
            },
            onClearImage = { front, fromPlan ->
                vm.clearCardImage(card.plan.id, card.membership.id, front, fromPlan)
            }
        )
    }
    if (showAddDoctor) {
        ProviderDialog(
            personName = selected?.name,
            onDismiss = { showAddDoctor = false },
            onConfirm = { draft, role ->
                vm.addProvider(draft, role)
                showAddDoctor = false
            }
        )
    }
    editingProvider?.let { provider ->
        ProviderDialog(
            existing = provider,
            personName = selected?.name,
            onDismiss = { editingProvider = null },
            onSave = {
                vm.updateProvider(it)
                editingProvider = null
            },
            onDelete = { deletingProvider = provider }
        )
    }
    checkingFor?.let { member ->
        PickPlanDialog(
            plans = checkablePlans.filter { it.hasDirectory },
            onDismiss = { checkingFor = null },
            onPick = { plan ->
                vm.checkNetwork(member, plan)
                checkingFor = null
            }
        )
    }
    phoneFor?.let { member ->
        RecordByPhoneDialog(
            member = member,
            plans = checkablePlans,
            onDismiss = { phoneFor = null },
            onConfirm = { plan, inNetwork, note ->
                vm.recordByPhone(member.provider.id, plan, inNetwork, note)
                phoneFor = null
            }
        )
    }
    historyFor?.let { member ->
        NetworkHistorySheet(
            member = member,
            onDismiss = { historyFor = null },
            onDeleteCheck = { deletingCheck = it }
        )
    }

    // Nothing on this screen is offered back afterwards, and each of these says why in its own words:
    // every one of them takes something with it that a restored row would not bring back — the
    // photographs of a card, the checks recorded against a doctor, or the evidence itself.
    removingMembership?.let { card ->
        ConfirmDeleteDialog(
            title = "Take ${card.memberName} off ${card.plan.displayName}?",
            body = "Their membership goes, along with any photographs of their own copy of the card. " +
                "The policy itself, and everybody else on it, stays. This can't be undone.",
            confirmLabel = "Remove",
            onDismiss = { removingMembership = null },
            onConfirm = { vm.deleteMembership(card.membership.id) }
        )
    }
    deletingPlan?.let { plan ->
        ConfirmDeleteDialog(
            title = "Delete ${plan.displayName}?",
            body = "The policy goes, along with everybody's membership of it and every photograph of " +
                "the card. If the cover has simply ended, archiving it keeps the record instead. " +
                "This can't be undone.",
            onDismiss = { deletingPlan = null },
            onConfirm = {
                vm.deletePlan(plan.id)
                editingPlan = null
            }
        )
    }
    deletingProvider?.let { provider ->
        ConfirmDeleteDialog(
            title = "Delete ${provider.name}?",
            body = "The doctor goes, along with every network check recorded against them — evidence " +
                "about somebody who is no longer here. Conditions they managed and doses they gave " +
                "are kept, and simply lose their clinician. This can't be undone.",
            onDismiss = { deletingProvider = null },
            onConfirm = {
                vm.deleteProvider(provider.id)
                editingProvider = null
            }
        )
    }
    deletingCheck?.let { check ->
        ConfirmDeleteDialog(
            title = "Delete this check?",
            body = "The record that this doctor was checked — and what the answer was — goes with it. " +
                "Delete it when the check was made against the wrong doctor; an answer that has " +
                "since changed is worth keeping, because the change is the point.",
            onDismiss = { deletingCheck = null },
            onConfirm = { vm.deleteCheck(check.id) }
        )
    }
}

// --- the cards --------------------------------------------------------------------------------------

// --- the care team ----------------------------------------------------------------------------------

// --- shared bits ------------------------------------------------------------------------------------

@Composable
private fun DirectoryMessage(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
internal fun EmptyState(title: String, body: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = onAction) { Text(action) }
    }
}
