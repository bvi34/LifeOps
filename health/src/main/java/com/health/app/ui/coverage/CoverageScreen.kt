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

@Composable
private fun CardsList(
    cards: List<CoverageCard>,
    plans: List<InsurancePlan>,
    personName: String?,
    images: CardImageStore,
    probingPlanId: String?,
    onView: (CoverageCard) -> Unit,
    onExport: (CoverageCard) -> Unit,
    onEditPlan: (InsurancePlan) -> Unit,
    onJoinPlan: (InsurancePlan) -> Unit,
    onRemove: (CoverageCard) -> Unit,
    onProbe: (InsurancePlan) -> Unit,
    onAdd: () -> Unit
) {
    // Policies the household holds that this person is not on. Worth showing rather than hiding:
    // adding a child to the family plan already in the app is one tap, and re-typing the carrier,
    // group number and phone numbers for them would be four minutes and three typos.
    val notOn = plans.filter { plan -> !plan.archived && cards.none { it.plan.id == plan.id } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (cards.isEmpty() && notOn.isEmpty()) {
            item {
                EmptyState(
                    title = "No cards yet",
                    body = "Add the insurance card ${personName ?: "this person"} carries — the " +
                        "carrier, the member number, and the numbers on the back. Photograph it and " +
                        "Health can produce a PDF of the card whenever somebody asks for one.",
                    action = "Add a card",
                    onAction = onAdd
                )
            }
        }

        items(cards, key = { it.membership.id }) { card ->
            CoverageCardRow(
                card = card,
                images = images,
                probing = probingPlanId == card.plan.id,
                onView = { onView(card) },
                onExport = { onExport(card) },
                onEditPlan = { onEditPlan(card.plan) },
                onProbe = { onProbe(card.plan) },
                onRemove = { onRemove(card) }
            )
        }

        if (notOn.isNotEmpty()) {
            item {
                SectionCard(title = "Also in the household") {
                    Text(
                        "${personName ?: "This person"} isn't on ${
                            if (notOn.size == 1) "this policy" else "these policies"
                        } yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    notOn.forEach { plan ->
                        ListItem(
                            headlineContent = { Text(plan.displayName) },
                            supportingContent = {
                                Text(plan.coverageKind.label, style = MaterialTheme.typography.bodySmall)
                            },
                            trailingContent = {
                                TextButton(onClick = { onJoinPlan(plan) }) { Text("Add to card") }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverageCardRow(
    card: CoverageCard,
    images: CardImageStore,
    probing: Boolean,
    onView: () -> Unit,
    onExport: () -> Unit,
    onEditPlan: () -> Unit,
    onProbe: () -> Unit,
    onRemove: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        card.plan.carrierName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    val subtitle = listOfNotNull(
                        card.plan.planName?.ifBlank { null },
                        card.plan.planType.takeIf { it != PlanType.OTHER }?.label,
                        card.plan.coverageKind.takeIf { it != CoverageKind.MEDICAL }?.label
                    ).joinToString(" · ")
                    if (subtitle.isNotBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                CoverageBadge(card.coverage.status)
            }

            // Masked in the list, in full on the card. The list is what somebody else can read over
            // your shoulder; the card is the thing you opened on purpose.
            card.membership.maskedMemberId?.let { masked ->
                Text("Member $masked", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                card.coverage.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            DirectoryLine(plan = card.plan, probing = probing, onProbe = onProbe)

            if (card.hasPhotos) {
                CardThumbnails(card, images)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = onView) { Text("Card") }
                TextButton(onClick = onExport) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("PDF")
                }
                TextButton(onClick = onEditPlan) { Text("Plan") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}

/** What Health knows about the plan's directory — and the one button that changes it. */
@Composable
private fun DirectoryLine(plan: InsurancePlan, probing: Boolean, onProbe: () -> Unit) {
    val text = when {
        !plan.hasDirectory ->
            "No provider directory recorded. Add its address to check doctors against this plan."
        plan.directoryStatus == DirectoryOutcome.NOT_CONFIGURED ->
            "Directory address recorded — not checked yet."
        plan.directoryStatus == DirectoryOutcome.REACHABLE ->
            "Directory reachable" + (plan.directoryCheckedAt?.let { ", checked ${formatStamp(it)}" } ?: "")
        else -> plan.directoryDetail ?: plan.directoryStatus.label
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (probing) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else if (plan.hasDirectory) {
            TextButton(onClick = onProbe) { Text("Test") }
        }
    }
}

@Composable
private fun CardThumbnails(card: CoverageCard, images: CardImageStore) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOfNotNull(card.frontImagePath, card.backImagePath).forEach { path ->
            rememberCardImage(images, path)?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = "Insurance card photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.height(52.dp).weight(1f)
                )
            }
        }
    }
}

@Composable
private fun CoverageBadge(status: CoverageStatus) {
    val color = when (status) {
        CoverageStatus.ENDED -> MaterialTheme.colorScheme.error
        CoverageStatus.ENDING_SOON, CoverageStatus.NOT_STARTED -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = MaterialTheme.shapes.small, color = color.copy(alpha = 0.12f)) {
        Text(
            status.label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * The card itself, both faces, with whatever photographs are attached and the button that turns the
 * whole thing into a PDF.
 *
 * The typed fields come first even when there are photographs, and the export does the same: a
 * photograph is authoritative and hard to read, while typed fields are legible and selectable, and
 * the person at the desk wants the second thing. The photograph is the evidence behind it.
 */
@Composable
private fun CardSheet(
    card: CoverageCard,
    images: CardImageStore,
    onDismiss: () -> Unit,
    onExport: (CoverageCard) -> Unit,
    onAttach: (android.net.Uri, front: Boolean, toPlan: Boolean) -> Unit,
    onClearImage: (front: Boolean, fromPlan: Boolean) -> Unit
) {
    var pickingFront by remember { mutableStateOf(true) }
    // A photo attached to the policy is the one envelope everybody on it shares; one attached to the
    // membership is this person's own card. Most households want the first, so it is the default.
    var attachToPlan by remember { mutableStateOf(true) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onAttach(it, pickingFront, attachToPlan) }
    }

    fun pick(front: Boolean) {
        pickingFront = front
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(card.plan.carrierName) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                card.layout.front.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                CardFaceBlock("Front", card.layout.front.fields.map { it.label to it.value })
                if (card.layout.back.fields.isNotEmpty()) {
                    CardFaceBlock("Back", card.layout.back.fields.map { it.label to it.value })
                }

                HorizontalDivider()
                Text("Photographs", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Saved on the device when you attach them, so the PDF can be produced later " +
                        "without asking for the picture again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = attachToPlan, onCheckedChange = { attachToPlan = it })
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (attachToPlan) "One card for everybody on this policy"
                        else "${card.memberName}'s own card",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                CardPhotoRow(
                    label = "Front",
                    path = card.frontImagePath,
                    images = images,
                    onPick = { pick(true) },
                    onClear = { onClearImage(true, attachToPlan) }
                )
                CardPhotoRow(
                    label = "Back",
                    path = card.backImagePath,
                    images = images,
                    onPick = { pick(false) },
                    onClear = { onClearImage(false, attachToPlan) }
                )

                Text(
                    Insurance.CARD_DISCLAIMER,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onExport(card) }) { Text("Export PDF") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun CardFaceBlock(title: String, rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun CardPhotoRow(
    label: String,
    path: String?,
    images: CardImageStore,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val bitmap = rememberCardImage(images, path)
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "$label of the card",
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(56.dp).weight(1f)
            )
        } else {
            Text(
                "$label — no photo",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
        TextButton(onClick = onPick) { Text(if (bitmap == null) "Attach" else "Replace") }
        if (bitmap != null) TextButton(onClick = onClear) { Text("Remove") }
    }
}

/**
 * Decode a stored card photo off the main thread and hold it for as long as the path is the same.
 *
 * `BitmapFactory` on the composition thread is a dropped frame per card, and the store is on disk
 * rather than in the database precisely so these can be large.
 */
@Composable
private fun rememberCardImage(images: CardImageStore, path: String?): ImageBitmap? {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = if (path == null) null
        else withContext(Dispatchers.IO) { images.load(path)?.asImageBitmap() }
    }
    return bitmap
}

// --- the care team ----------------------------------------------------------------------------------

@Composable
private fun DoctorsList(
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
private fun NetworkHistorySheet(
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
private fun EmptyState(title: String, body: String, action: String, onAction: () -> Unit) {
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
